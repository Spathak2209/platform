/**
 * This file is part of platform-controller.
 *
 * platform-controller is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * platform-controller is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with platform-controller.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.hobbit.controller;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import com.spotify.docker.client.messages.Volume;
import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.vocabulary.RDF;
import org.hobbit.controller.docker.CloudClusterManager;
import org.hobbit.controller.config.HobbitConfig;
import org.hobbit.controller.data.ExperimentConfiguration;
import org.hobbit.controller.data.ExperimentStatus;
import org.hobbit.controller.data.ExperimentStatus.States;
import org.hobbit.controller.docker.ClusterManager;
import org.hobbit.controller.docker.MetaDataFactory;
import org.hobbit.controller.execute.ExperimentAbortTimerTask;
import org.hobbit.core.Commands;
import org.hobbit.core.Constants;
import org.hobbit.core.data.BenchmarkMetaData;
import org.hobbit.core.data.SystemMetaData;
import org.hobbit.core.data.status.ControllerStatus;
import org.hobbit.core.data.status.RunningExperiment;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hobbit.utils.rdf.RdfHelper;
import org.hobbit.vocab.HOBBIT;
import org.hobbit.vocab.HobbitErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spotify.docker.client.exceptions.DockerException;

import static org.hobbit.controller.docker.DockerUtility.getDockerClient;

/**
 * This class encapsulates (and synchronizes) all methods that are applied on a
 * running experiment.
 *
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public class ExperimentManager implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentManager.class);
    private static final int DEFAULT_MAX_EXECUTION_TIME = 20 * 60 * 1000;
    public static final String MAX_EXECUTION_TIME_KEY = "MAX_EXECUTION_TIME";


    /**
     * Time interval the experiment manager waits before it checks for the an
     * experiment to start. It is larger than {@link #CHECK_FOR_NEW_EXPERIMENT}
     * since the system needs some time to initialize all components and we want to
     * make sure that everything is up and running.
     */
    public static final long CHECK_FOR_FIRST_EXPERIMENT = 30000;
    /**
     * Time interval with which the experiment manager checks for a new experiment
     * to start.
     */
    public static final long CHECK_FOR_NEW_EXPERIMENT = 10000;
    /**
     * Default time an experiment has to terminate after it has been started.
     */
    public long defaultMaxExecutionTime;
    /**
     * The controller this manager belongs to.
     */
    private PlatformController controller;
    /**
     * Mutex used to synchronize the access to the {@link #experimentStatus}
     * instance.
     */
    private Semaphore experimentMutex = new Semaphore(1);
    /**
     * Status of the current experiment. <code>null</code> if no benchmark is
     * running.
     */
    private ExperimentStatus experimentStatus = null;
    /**
     * Timer used to trigger the creation of the next benchmark.
     */
    private Timer expStartTimer;
    private String systemServiceName;
    private Volume systemAdapterVolume;

    public ExperimentManager(PlatformController controller) {
        this(controller, CHECK_FOR_FIRST_EXPERIMENT, CHECK_FOR_NEW_EXPERIMENT);
    }

    protected ExperimentManager(PlatformController controller, long checkForFirstExperiment,
            long checkForNewExperiment) {
        this.controller = controller;

        try {
            // TODO environment variable should have been used there
            // TODO global static method in hobbit core for retrieving values like this
            defaultMaxExecutionTime = (System.getenv().containsKey(MAX_EXECUTION_TIME_KEY) ? Long
                    .parseLong(System.getenv().get(MAX_EXECUTION_TIME_KEY)):DEFAULT_MAX_EXECUTION_TIME);

        } catch (Exception e) {
            LOGGER.debug("Could not get execution time from env, using default value..");
        }

        expStartTimer = new Timer();
        expStartTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    // trigger the creation of the next benchmark
                    createNextExperiment();
                } catch (Throwable e) {
                    LOGGER.error(
                            "The experiment starting timer got an unexpected exception. Trying to handle the corrupted experiment if there is one.",
                            e);
                    // If there is an experiment status, it will be handled in
                    // this method
                    handleExperimentTermination();
                }
            }
        }, checkForFirstExperiment, checkForNewExperiment);
    }

    /**
     * Creates the next experiment if there is no experiment running and there is an
     * experiment waiting in the queue.
     */
    public void createNextExperiment(){
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning.", e);
            return;
        }
        try {
            // if there is no benchmark running, the queue has been
            // initialized and interfaces is healthy

            //ResourceUsageInformation su = controller.getResInfoCollector().getSystemUsageInformation();
            if ((experimentStatus == null) && (controller.queue != null)) {
                ClusterManager clusterManager = this.controller.clusterManager;

                if(clusterManager instanceof CloudClusterManager)
                    ((CloudClusterManager) clusterManager).reactOnQueue(controller.queue);


                if(controller.queue.listAll().size()==0)
                    return;


                ExperimentConfiguration config = controller.queue.getNextExperiment();
                LOGGER.debug("Trying to start the next benchmark.");
                if (config == null) {
                    LOGGER.debug("There is no experiment to start.");
                    return;
                }


                if(!(clusterManager instanceof CloudClusterManager) && !clusterManager.isClusterHealthy()) {
                    LOGGER.error("Can not start next experiment in the queue, cluster is NOT HEALTHY. " +
                            "Check your cluster consistency or adjust SWARM_NODE_NUMBER environment variable." +
                            " Expected number of nodes: " + clusterManager.getExpectedNumberOfNodes() +
                            " Current number of nodes: " + clusterManager.getNumberOfNodes());
                    return;
                }


                LOGGER.info("Creating next experiment " + config.id + " with benchmark " + config.benchmarkUri
                        + " and system " + config.systemUri + " to the queue.");

                experimentStatus = new ExperimentStatus(config, PlatformController.generateExperimentUri(config.id));

                if(clusterManager instanceof CloudClusterManager){
                    CloudClusterManager cloudClusterManager = (CloudClusterManager)clusterManager;
                    String clusterConfiguration = getClusterConfiguration(config);
                    try {
                        experimentStatus.setState(States.CLOUD_RESOURCES_PREPARATION);
                        cloudClusterManager.createCluster(clusterConfiguration);
                        experimentStatus.setState(States.PREPARATION);
                    }
                    catch (Exception e) {
                        LOGGER.error("Could not create cluster: {}",e.getLocalizedMessage());
                        return;
                    }
                }

                BenchmarkMetaData benchmark = controller.imageManager().getBenchmark(config.benchmarkUri);
                if ((benchmark == null) || (benchmark.mainImage == null)) {
                    experimentStatus = new ExperimentStatus(config, PlatformController.generateExperimentUri(config.id),
                            this, defaultMaxExecutionTime);
                    experimentStatus.addError(HobbitErrors.BenchmarkImageMissing);
                    throw new Exception("Couldn't find image name for benchmark " + config.benchmarkUri);
                }

                SystemMetaData system = controller.imageManager().getSystem(config.systemUri);
                if ((system == null) || (system.mainImage == null)) {
                    experimentStatus = new ExperimentStatus(config, PlatformController.generateExperimentUri(config.id),
                            this, defaultMaxExecutionTime);
                    experimentStatus.addError(HobbitErrors.SystemImageMissing);
                    throw new Exception("Couldn't find image name for system " + config.systemUri);
                }

                prefetchImages(benchmark, system);

                // time an experiment has to terminate after it has been started
                long maxExecutionTime = defaultMaxExecutionTime;

                // try to load benchmark timeouts from config file
                try {
                    HobbitConfig hobbitCfg = HobbitConfig.loadConfig();
                    HobbitConfig.TimeoutConfig timeouts = hobbitCfg.getTimeout(config.benchmarkUri);
                    if (timeouts != null) {
                        if (config.challengeUri != null) {
                            if (timeouts.challengeTimeout != -1) {
                                maxExecutionTime = timeouts.challengeTimeout;
                                LOGGER.info("Using challenge timeout: {}", maxExecutionTime);
                            } else {
                                LOGGER.warn("Challenge timeout for given benchmark is not set, using default value..");
                            }
                        } else {
                            if (timeouts.benchmarkTimeout != -1) {
                                maxExecutionTime = timeouts.benchmarkTimeout;
                                LOGGER.info("Using benchmark timeout:", maxExecutionTime);
                            } else {
                                LOGGER.warn("Benchmark timeout is not set, using default value..");
                            }
                        }
                    } else {
                        LOGGER.error("Timeouts for given benchmark are not set, using default value..");
                    }
                } catch (Exception e) {
                    LOGGER.error("Could not load timeouts config ({}). Using default value {}ms.", e.getMessage(),
                            defaultMaxExecutionTime);
                }

                // start experiment timer/status
                experimentStatus.startAbortionTimer(this, maxExecutionTime);
                experimentStatus.setState(States.INIT);

                String serializedBenchmarkParams = getSerializedBenchmarkParams(config, benchmark);
                LOGGER.info("Creating benchmark controller " + benchmark.mainImage);
                String containerId = controller.containerManager.startContainer(benchmark.mainImage,
                        Constants.CONTAINER_TYPE_BENCHMARK, null,
                        new String[] { Constants.RABBIT_MQ_HOST_NAME_KEY + "=" + controller.rabbitMQHostName(),
                                Constants.HOBBIT_SESSION_ID_KEY + "=" + config.id,
                                Constants.HOBBIT_EXPERIMENT_URI_KEY + "=" + experimentStatus.experimentUri,
                                Constants.BENCHMARK_PARAMETERS_MODEL_KEY + "=" + serializedBenchmarkParams /*config.serializedBenchParams*/,
                                Constants.SYSTEM_URI_KEY + "=" + config.systemUri },
                        null, config.id);
                if (containerId == null) {
                    experimentStatus.addError(HobbitErrors.BenchmarkCreationError);
                    throw new Exception("Couldn't create benchmark controller " + config.benchmarkUri);
                }

                experimentStatus.setBenchmarkContainer(containerId);

                LOGGER.info("Creating system " + system.mainImage);

                String serializedSystemParams = getSerializedSystemParams(config, benchmark, system);
                systemAdapterVolume = getDockerClient().createVolume();
                systemServiceName = controller.containerManager.startContainer(system.mainImage,
                        Constants.CONTAINER_TYPE_SYSTEM, experimentStatus.getBenchmarkContainer(),
                        new String[] { Constants.RABBIT_MQ_HOST_NAME_KEY + "=" + controller.rabbitMQHostName(),
                                Constants.HOBBIT_SESSION_ID_KEY + "=" + config.id,
                                Constants.SYSTEM_PARAMETERS_MODEL_KEY + "=" + serializedSystemParams },
                        null, config.id, new String[]{ systemAdapterVolume.name()+":/share" });

                if (systemServiceName == null) {
                    LOGGER.error("Couldn't start the system. Trying to cancel the benchmark.");
                    forceBenchmarkTerminate_unsecured(HobbitErrors.SystemCreationError);
                    throw new Exception("Couldn't start the system " + config.systemUri);
                } else {
                    experimentStatus.setSystemContainer(systemServiceName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Exception while trying to start a new benchmark. Removing it from the queue.", e);
            // Add an error if there is a model but no error was added
            if (experimentStatus != null) {
                experimentStatus.addErrorIfNonPresent(HobbitErrors.UnexpectedError);
            }
            handleExperimentTermination_unsecured();
        } finally {
            experimentMutex.release();
        }
    }

    protected static String getSerializedBenchmarkParams(ExperimentConfiguration config, BenchmarkMetaData benchmark) {


        Model benchParams = RabbitMQUtils.readModel(config.serializedBenchParams);
        Resource experiment = benchParams.getResource(Constants.NEW_EXPERIMENT_URI);
        Property defaultValProperty = benchmark.rdfModel.getProperty("http://w3id.org/hobbit/vocab#defaultValue");
        if (benchmark.rdfModel.contains(null, RDF.type, HOBBIT.Parameter)){

            // Get an iterator for all these parameters
            ResIterator iterator = benchmark.rdfModel.listResourcesWithProperty(RDF.type, HOBBIT.Parameter);
            while (iterator.hasNext()) {
                // Get the parameter
                Property parameter = benchParams.getProperty(((Resource)iterator.next()).getURI());
                NodeIterator benchParamValue = benchParams.listObjectsOfProperty(experiment, parameter);
                if(!benchParamValue.hasNext()){

                    NodeIterator defaultParamValue = benchmark.rdfModel.listObjectsOfProperty(parameter, defaultValProperty);
                    while (defaultParamValue.hasNext()) {
                        Literal valueLiteral = (Literal) defaultParamValue.next();
                        benchParams.add(experiment, parameter, valueLiteral.getString());
                    }
                }
            }
        }

        return RabbitMQUtils.writeModel2String(benchParams);
    }

    // FIXME add javadoc
    // Static method for easier testing
    protected static String getSerializedSystemParams(ExperimentConfiguration config, BenchmarkMetaData benchmark,
            SystemMetaData system) {

        Model systemModel = MetaDataFactory.getModelWithUniqueSystem(system.rdfModel, config.systemUri);
        Model benchParams = RabbitMQUtils.readModel(config.serializedBenchParams);
        Resource systemResource = systemModel.getResource(config.systemUri);
        Resource experiment = benchParams.getResource(Constants.NEW_EXPERIMENT_URI);

        // Check the benchmark model for parameters that should be forwarded to the
        // system
        if (benchmark.rdfModel.contains(null, RDF.type, HOBBIT.ForwardedParameter)){
            Property parameter;
            NodeIterator objIterator;
            // Get an iterator for all these parameters
            ResIterator iterator = benchmark.rdfModel.listResourcesWithProperty(RDF.type, HOBBIT.ForwardedParameter);
            while (iterator.hasNext()) {
                // Get the parameter
                parameter = benchmark.rdfModel.getProperty(iterator.next().getURI());
                // Get its value
                objIterator = benchParams.listObjectsOfProperty(experiment, parameter);
                // If there is a value, add it to the system model
                while (objIterator.hasNext()) {
                    Literal valueLiteral = (Literal)objIterator.next();
                    systemModel.add(systemResource, parameter, valueLiteral.getString());
                    //systemModel.add(systemResource, parameter, objIterator.next());
                }
            }
        }
        //adding parameters from system.ttl
        if (system.rdfModel.contains(null, RDF.type, HOBBIT.Parameter)){
            NodeIterator objIterator;
            // Get an iterator for all these parameters
            Property defaultValProperty = system.rdfModel.getProperty("http://w3id.org/hobbit/vocab#defaultValue");
            ResIterator iterator = system.rdfModel.listResourcesWithProperty(RDF.type, HOBBIT.Parameter);
            while (iterator.hasNext()) {
                Property parameter = system.rdfModel.getProperty(((Resource)iterator.next()).getURI());
                objIterator = system.rdfModel.listObjectsOfProperty(parameter, defaultValProperty);
                // If there is a value, add it to the system model
                while (objIterator.hasNext()) {
                    Literal valueLiteral = (Literal)objIterator.next();
                    systemModel.add(systemResource, parameter, valueLiteral.getString());
                    //benchParams.remove(experiment, parameter, node);
                }
            }
        }
        //config.serializedBenchParams = RabbitMQUtils.writeModel2String(benchParams);
        return RabbitMQUtils.writeModel2String(systemModel);
    }

    public static String getClusterConfiguration(ExperimentConfiguration config) {

        // Check the benchmark model for parameters that should be forwarded to the
        // system

        Model benchParams = RabbitMQUtils.readModel(config.serializedBenchParams);

        Property parameter;
        //NodeIterator objIterator;
        //Resource systemResource = systemModel.getResource(config.systemUri);
        Resource experiment = benchParams.listResourcesWithProperty(RDF.type, HOBBIT.Experiment).next();
        NodeIterator objIterator = benchParams.listObjectsOfProperty(experiment, new PropertyImpl(config.benchmarkUri+"#clusterConfig"));
        while (objIterator.hasNext()) {
            String ret = objIterator.next().asLiteral().getString();
            return ret;
        }

        return "";//RabbitMQUtils.writeModel2String(systemModel);
    }

    protected void prefetchImages(BenchmarkMetaData benchmark, SystemMetaData system) throws Exception {
        Set<String> usedImages = new HashSet<String>();
        usedImages.add(benchmark.mainImage);
        usedImages.addAll(benchmark.usedImages);
        usedImages.add(system.mainImage);
        usedImages.addAll(benchmark.usedImages);
        // pull all used images
        for (String image : usedImages) {
            LOGGER.info("Prefetching image {}", image);
            controller.containerManager.pullImage(image);
        }
    }

    /**
     * Sets the result model of the current running experiment by transforming the
     * given data into an RDF model using the given function while owning the
     * {@link #experimentStatus} object and therefore blocking all other operations
     * on that object.
     *
     * @param data
     *            binary data containing a serialized RDF model
     * @param function
     *            a deserialization function transforming the binary data into an
     *            RDF model
     */
    public void setResultModel(byte[] data, Function<? super byte[], ? extends Model> function) {
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning.", e);
            return;
        }
        try {
            setResultModel_unsecured(function.apply(data));
        } finally {
            experimentMutex.release();
        }
    }

    /**
     * Sets the result model of the current running experiment.
     *
     * @param model
     *            the result model
     */
    public void setResultModel(Model model) {
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning.", e);
            return;
        }
        try {
            setResultModel_unsecured(model);
        } finally {
            experimentMutex.release();
        }
    }

    /**
     * Sets the result model of the current running experiment.
     *
     * @param model
     *            the result model
     */
    private void setResultModel_unsecured(Model model) {
        if (experimentStatus != null) {
            experimentStatus.setOrMergeResultModel(model);
        } else {
            LOGGER.error("Got a result model while there is no experiment running.");
        }
    }

    /**
     * This method handles the storing of the experiment results in the database,
     * the removing of the experiment from the queue and its closing in a
     * synchronized way.
     */
    public synchronized void handleExperimentTermination() {
        try {
            experimentMutex.acquire();
            handleExperimentTermination_unsecured();
            experimentMutex.release();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning.", e);
        }
    }

    private synchronized void handleExperimentTermination_unsecured() {
        if (experimentStatus != null) {
            LOGGER.info("Benchmark terminated. Experiment " + experimentStatus.config.id
                    + " has been finished. Removing it from the queue and setting the config to null.");
            // Close the experiment to stop its internal timer
            IOUtils.closeQuietly(experimentStatus);
            long endTimestamp = System.currentTimeMillis();
            // TODO add information about the hardware

            // Store the result model in DB
            // choose the correct graph
            String graphUri = Constants.PUBLIC_RESULT_GRAPH_URI;
            if (experimentStatus.config.challengeUri != null) {
                // check if challenge is repeatable (by selecting data from all graphs)
                boolean repeatable = false;
                Model challengeModel = controller.getChallengeFromUri(experimentStatus.config.challengeUri, null);
                if (challengeModel != null) {
                    Resource challenge = challengeModel.getResource(experimentStatus.config.challengeUri);
                    repeatable = RdfHelper.getLiteral(challengeModel, challenge, HOBBIT.registrationCutoffDate) != null;
                }

                if (!repeatable) {
                    graphUri = Constants.PRIVATE_RESULT_GRAPH_URI;
                }
            }

            //if cluster is not healthy add error message to experimentStatus
            try {
                ClusterManager clusterManager = this.controller.clusterManager;
                boolean isHealthy = clusterManager.isClusterHealthy();
                if(!isHealthy) {
                    LOGGER.error("Cluster became unhealthy during the experiment! Some nodes are down." +
                            " Expected number of nodes: "+clusterManager.getExpectedNumberOfNodes()+
                            " Current number of nodes: "+clusterManager.getNumberOfNodes());

                    experimentStatus.addError(HobbitErrors.ClusterNotHealthy);
                }
            } catch (DockerException e) {
                LOGGER.error("Could not get cluster health status. ", e);
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted. Could not get cluster health status. ", e);
            }

            Model resultModel = experimentStatus.getResultModel();
            if (resultModel == null) {
                experimentStatus.addError(HobbitErrors.UnexpectedError);
                resultModel = experimentStatus.getResultModel();
            }
            experimentStatus.addMetaDataToResult(controller.imageManager(), endTimestamp);
            // Send insert query
            if (!controller.storage().sendInsertQuery(resultModel, graphUri)) {
                if (resultModel != null) {
                    StringWriter writer = new StringWriter();
                    resultModel.write(writer, "TTL");
                    LOGGER.error("Error while storing the result model of the experiment. Logging it: ",
                            writer.toString().replace('\n', ' '));
                }
            }
            // We have to remove the config from the queue
            controller.queue.remove(experimentStatus.config);
            // Send experiment URI to the analysis component if the result is public
            if (graphUri.equals(Constants.PUBLIC_RESULT_GRAPH_URI)) {
                try {
                    controller.analyzeExperiment(experimentStatus.experimentUri);
                } catch (Exception e) {
                    LOGGER.error("Could not send task \"{}\" to AnalyseQueue.",
                            experimentStatus.getConfig().challengeTaskUri);
                }
            }
            // publish experiment results (if needed)
            // controller.publishChallengeForExperiment(experimentStatus.config);
            // Remove the experiment status object
            experimentStatus = null;

            if(systemAdapterVolume!=null) {
                try {
                    getDockerClient().removeVolume(systemAdapterVolume);
                } catch (Exception e) {
                    LOGGER.error("Failed to remove volume: {}", e.getLocalizedMessage());
                }
            }
        }
    }

    /**
     * Forces the benchmark controller and its child containers to terminate. If the
     * given error is not <code>null</code> it is added to the result model of the
     * experiment.
     *
     * @param error
     *            error that is added to the result model of the experiment
     */
    private void forceBenchmarkTerminate_unsecured(Resource error) {
        if (experimentStatus != null) {
            String parent = experimentStatus.getBenchmarkContainer();
            controller.containerManager.removeParentAndChildren(parent);
            if (error != null) {
                experimentStatus.addError(error);
            }
        }
    }

    /**
     * Handles the termination of the container with the given container Id and the
     * given exit code.
     *
     * @param containerId
     *            Id of the terminated container
     * @param exitCode
     *            exit code of the termination
     */
    public void notifyTermination(String containerId, int exitCode) {
        boolean consumed = false;
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning.", e);
            return;
        }
        try {
            if (experimentStatus != null) {
                // If this container is the benchmark controller of the
                // current
                // experiment
                if (containerId.equals(experimentStatus.getBenchmarkContainer())) {
                    experimentStatus.setState(ExperimentStatus.States.STOPPED);
                    if (exitCode != 0) {
                        LOGGER.warn("The benchmark container " + experimentStatus.getBenchmarkContainer()
                                + " terminated with an exit code != 0.");
                        experimentStatus.addErrorIfNonPresent(HobbitErrors.BenchmarkCrashed);
                    }
                    handleExperimentTermination_unsecured();
                    // If this is the system container and benchmark and
                    // system are not running
                    consumed = true;
                } else if (containerId.equals(experimentStatus.getSystemContainer())
                        && (experimentStatus.getState() == ExperimentStatus.States.INIT)) {
                    LOGGER.info("The system has been stopped before the benchmark has been started. Aborting.");
                    // Cancel the experiment
                    forceBenchmarkTerminate_unsecured(HobbitErrors.SystemCrashed);
                    consumed = true;
                }
            }
        } finally {
            experimentMutex.release();
        }
        if (!consumed) {
            LOGGER.info("Sending broadcast message...");
            // send a message using sendToCmdQueue(command,
            // data) comprising a command that indicates that a
            // container terminated and the container name
            String containerName = controller.containerManager.getContainerName(containerId);
            if (containerName != null){
                try {
                    controller.sendToCmdQueue(Constants.HOBBIT_SESSION_ID_FOR_BROADCASTS,
                            Commands.DOCKER_CONTAINER_TERMINATED,
                            RabbitMQUtils.writeByteArrays(null,
                                    new byte[][] { RabbitMQUtils.writeString(containerName) },
                                    new byte[] { (byte) exitCode }),
                            null);
                } catch (IOException e) {
                    LOGGER.error("Couldn't send the " + Constants.HOBBIT_SESSION_ID_FOR_BROADCASTS
                            + " message for container " + containerName + " to the command queue.", e);
                }
            } else {
                LOGGER.info("Unknown container " + containerId + " stopped with exitCode=" + exitCode);
            }
        }
    }

    /**
     * Handles the messages that either the system or the benchmark controller are
     * ready.
     *
     * @param systemReportedReady
     *            <code>true</code> if the message was sent by the system,
     *            <code>false</code> if the benchmark controller is ready
     */
    public void systemOrBenchmarkReady(boolean systemReportedReady) {
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning.", e);
            return;
        }
        try {
            // If there is an experiment waiting with the state INIT and if
            // both - system and benchmark are ready
            if ((experimentStatus != null) && (experimentStatus.setReadyAndCheck(systemReportedReady)
                    && (experimentStatus.getState() == ExperimentStatus.States.INIT))) {
                try {
                    startBenchmark_unsecured();
                } catch (IOException e) {
                    // Let's retry this
                    try {
                        startBenchmark_unsecured();
                    } catch (IOException e2) {
                        LOGGER.error("Couldn't sent start signal to the benchmark controller. Terminating experiment.",
                                e2);
                        // We have to terminate the experiment
                        forceBenchmarkTerminate_unsecured(HobbitErrors.UnexpectedError);
                    }
                }
            }
        } finally {
            experimentMutex.release();
        }
    }

    /**
     * Sends the start message to the benchmark controller.
     *
     * @throws IOException
     *             if there is a communication problem or if the name of the system
     *             container can not be retrieved from the docker daemon
     */
    private void startBenchmark_unsecured() throws IOException {
        String containerName = controller.containerManager.getContainerName(experimentStatus.getSystemContainer());
        if (containerName == null) {
            throw new IOException(
                    "Couldn't derive container name of the system container for sending start message to the benchmark.");
        }
        try {
            // send the start signal (we are only reading the config object
            // and do not need to wait for the mutex)
            controller.sendToCmdQueue(experimentStatus.config.id, Commands.START_BENCHMARK_SIGNAL,
                    RabbitMQUtils.writeString(containerName), null);
            experimentStatus.setState(ExperimentStatus.States.STARTED);
        } catch (IOException e) {
            LOGGER.error("Couldn't send " + Commands.START_BENCHMARK_SIGNAL + " signal for experiment "
                    + experimentStatus.config.id, e);
            throw e;
        }
    }

    /**
     * Adds the status of the current experiment to the given status object.
     *
     * @param status
     *            the status object to which the data should be added
     */
    public void addStatusInfo(ControllerStatus status, String userName) {
        // copy the pointer to the experiment status to make sure that we can
        // read it even if another thread sets the pointer to null. This gives
        // us the possibility to read the status without acquiring the
        // experimentMutex.
        ExperimentStatus currentStatus = experimentStatus;
        if (currentStatus != null) {
            ExperimentConfiguration config = currentStatus.getConfig();
            RunningExperiment experiment = new RunningExperiment();
            if (config != null) {
                experiment.benchmarkUri = config.benchmarkUri;
                experiment.systemUri = config.systemUri;
                experiment.experimentId = config.id;
                experiment.challengeUri = config.challengeUri;
                experiment.challengeTaskUri = config.challengeTaskUri;
                experiment.canBeCanceled = userName != null && userName.equals(config.userName);
                experiment.dateOfExecution = config.executionDate != null ? config.executionDate.getTimeInMillis() : 0;
            }
            experiment.startTimestamp = currentStatus.getStartTimeStamp();
            experiment.timestampOfAbortion = currentStatus.getAbortionTimeStamp();
            States exState = currentStatus.getState();
            if (exState != null) {
                experiment.status = exState.description;
            }
            status.experiment = experiment;
        }
    }

    /**
     * Changes the state of the internal experiment to
     * {@link ExperimentStatus.States#EVALUATION}.
     */
    public void taskGenFinished() {
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for the experiment mutex. Returning empty status.", e);
            return;
        }
        try {
            if (experimentStatus != null) {
                experimentStatus.setState(ExperimentStatus.States.EVALUATION);
            }
        } finally {
            experimentMutex.release();
        }
    }

    /**
     * Called by the {@link ExperimentAbortTimerTask} if the maximum runtime of an
     * experiment has been reached.
     *
     * @param expiredState
     *            the experiment status the timer was working on which is used to
     *            make sure that the timer was started for the currently running
     *            experiment.
     */
    public void notifyExpRuntimeExpired(ExperimentStatus expiredState) {
        Objects.requireNonNull(expiredState);
        try {
            experimentMutex.acquire();
        } catch (InterruptedException e) {
            LOGGER.error(
                    "Interrupted while waiting for the experiment mutex. The experiment abortion time won't be checked.",
                    e);
            return;
        }
        try {
            // If this is the currently running experiment
            if ((experimentStatus != null) && (expiredState.experimentUri.equals(expiredState.experimentUri))) {
                // If the experiment hasn't been stopped
                if (experimentStatus.getState() != States.STOPPED) {
                    LOGGER.error("The experiment {} took too much time. Forcing termination.",
                            experimentStatus.experimentUri);
                    forceBenchmarkTerminate_unsecured(HobbitErrors.ExperimentTookTooMuchTime);
                }
            } else {
                LOGGER.warn(
                        "Got a timeout notification for an experiment that does not match the current experiment. It will be ignored.");
            }
        } finally {
            experimentMutex.release();
        }
    }

    /**
     * Stops the currently running experiment if it has the given experiment id.
     * 
     * @param experimentId
     *            the id of the experiment that should be stopped
     */
    public void stopExperimentIfRunning(String experimentId) {
//        try {
//            experimentMutex.acquire();
//        } catch (InterruptedException e) {
//            LOGGER.error(
//                    "Interrupted while waiting for the experiment mutex. Won't check the experiment regarding the requested termination.",
//                    e);
//            return false;
//        }
        try {
            // If this is the currently running experiment
            if ((experimentStatus != null) && (experimentStatus.config.id.equals(experimentId))) {
                // If the experiment hasn't been stopped
                if (experimentStatus.getState() != States.STOPPED) {
                    LOGGER.error("The experiment {} was stopped by the user. Forcing termination.",
                            experimentStatus.experimentUri);
                    forceBenchmarkTerminate_unsecured(HobbitErrors.TerminatedByUser);
                    //experimentStatus.setState(States.STOPPED);
                    handleExperimentTermination_unsecured();
                }
            }
        } finally {
            //experimentMutex.release();
        }

    }

    public String getSystemServiceName() {
        return systemServiceName;
    }

//    public String getSystemContainerId() {
//
//        if(systemServiceName==null)
//            return null;
//        try {
//            Task task = controller.containerManager.getContainerInfo(systemServiceName);
//            while(task.status()==null ||
//                  task.status().containerStatus()==null ||
//                  task.status().containerStatus().containerId()==null){
//                LOGGER.debug("Waiting for task status");
//                Thread.sleep(500);
//            }
//            String ret = task.status().containerStatus().containerId().substring(0, 12);
//            return ret;
//
//        } catch (Exception e) {
//            LOGGER.error("Failed to get systemContainerId: {}", e.getLocalizedMessage());
//        }
//        return null;
//    }

    public Volume getSystemContainerVolume() {
        return systemAdapterVolume;
    }

    @Override
    public void close() throws IOException {
        expStartTimer.cancel();
    }

}
