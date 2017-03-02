package org.hobbit.controller;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.NodeIterator;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.hobbit.controller.analyze.ExperimentAnalyzer;
import org.hobbit.controller.data.ExperimentConfiguration;
import org.hobbit.controller.docker.ContainerManager;
import org.hobbit.controller.docker.ContainerManagerImpl;
import org.hobbit.controller.docker.ContainerStateObserver;
import org.hobbit.controller.docker.ContainerStateObserverImpl;
import org.hobbit.controller.docker.ContainerTerminationCallback;
import org.hobbit.controller.docker.ImageManager;
import org.hobbit.controller.docker.ImageManagerImpl;
import org.hobbit.controller.health.ClusterHealthChecker;
import org.hobbit.controller.health.ClusterHealthCheckerImpl;
import org.hobbit.controller.queue.ExperimentQueue;
import org.hobbit.controller.queue.ExperimentQueueImpl;
import org.hobbit.core.Commands;
import org.hobbit.core.Constants;
import org.hobbit.core.FrontEndApiCommands;
import org.hobbit.core.components.AbstractCommandReceivingComponent;
import org.hobbit.core.data.ConfiguredExperiment;
import org.hobbit.core.data.ControllerStatus;
import org.hobbit.core.data.StartCommandData;
import org.hobbit.core.data.StopCommandData;
import org.hobbit.core.data.SystemMetaData;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hobbit.storage.client.StorageServiceClient;
import org.hobbit.storage.queries.SparqlQueries;
import org.hobbit.utils.rdf.RdfHelper;
import org.hobbit.vocab.HOBBIT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.MessageProperties;
import com.spotify.docker.client.messages.Container;

/**
 * This class implements the functionality of the central platform controller.
 *
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public class PlatformController extends AbstractCommandReceivingComponent
        implements ContainerTerminationCallback, ExperimentAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformController.class);

    /**
     * The current version of the platform.
     * 
     * TODO Find a way to load the version automatically from the pom file.
     */
    public static final String PLATFORM_VERSION = "1.0.0";

    private static final String DEPLOY_ENV = System.getProperty("DEPLOY_ENV", "production");
    private static final String DEPLOY_ENV_TESTING = "testing";

    /**
     * Default time an experiment has to terminate after it has been started.
     */
    public static final long DEFAULT_MAX_EXECUTION_TIME = 30 * 60 * 1000;
    // every 60 mins
    public static final long PUBLISH_CHALLENGES = 60 * 60 * 1000;

    /**
     * RabbitMQ channel between front end and platform controller.
     */
    protected Channel frontEnd2Controller;
    /**
     * The handler for requests coming from the front end.
     */
    protected DefaultConsumer frontEndApiHandler;
    /**
     * RabbitMQ channel between front end and platform controller.
     */
    protected Channel controller2Analysis;
    /**
     * A manager for Docker containers.
     */
    protected ContainerManager containerManager;
    /**
     * The observer of docker containers.
     */
    protected ContainerStateObserver containerObserver;
    /**
     * The queue containing experiments that are waiting for their execution.
     */
    protected ExperimentQueue queue;
    /**
     * Health checker used to make sure that the cluster has the preconfigured
     * hardware.
     */
    protected ClusterHealthChecker healthChecker = new ClusterHealthCheckerImpl();
    /**
     * A simple mutex that is used to wait for a termination signal for the
     * controller.
     */
    private Semaphore terminationMutex = new Semaphore(0);
    /**
     * Threadsafe JSON parser.
     */
    private Gson gson = new Gson();
    /**
     * Manager of benchmark and system images.
     */
    private ImageManager imageManager;
    /**
     * Last experiment id that has been used.
     */
    private long lastIdTime = 0;

    protected StorageServiceClient storage;

    protected ExperimentManager expManager;

    /**
     * Timer used to trigger publishing of challenges
     */
    private Timer challengePublishTimer;

    @Override
    public void init() throws Exception {
        // First initialize the super class
        super.init();
        LOGGER.debug("Platform controller initialization started.");

        // create container manager
        containerManager = new ContainerManagerImpl();
        LOGGER.debug("Container manager initialized.");
        // Create container observer (polls status every 5s)
        containerObserver = new ContainerStateObserverImpl(containerManager, 5 * 1000);
        containerObserver.addTerminationCallback(this);
        // Tell the manager to add container to the observer
        containerManager.addContainerObserver(containerObserver);

        containerObserver.startObserving();
        LOGGER.debug("Container observer initialized.");

        imageManager = new ImageManagerImpl();
        LOGGER.debug("Image manager initialized.");

        frontEnd2Controller = connection.createChannel();
        frontEndApiHandler = new DefaultConsumer(frontEnd2Controller) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
                    throws IOException {
                if (body.length > 0) {
                    BasicProperties replyProperties;
                    replyProperties = new BasicProperties.Builder().correlationId(properties.getCorrelationId())
                            .deliveryMode(2).build();
                    handleFrontEndCmd(body, properties.getReplyTo(), replyProperties);
                }
            }
        };
        frontEnd2Controller.queueDeclare(Constants.FRONT_END_2_CONTROLLER_QUEUE_NAME, false, false, true, null);
        frontEnd2Controller.basicConsume(Constants.FRONT_END_2_CONTROLLER_QUEUE_NAME, true, frontEndApiHandler);

        controller2Analysis = connection.createChannel();
        controller2Analysis.queueDeclare(Constants.CONTROLLER_2_ANALYSIS_QUEUE_NAME, false, false, true, null);

        queue = new ExperimentQueueImpl();

        storage = StorageServiceClient.create(connection);

        // the experiment manager should be the last module to create since it
        // directly starts to use the other modules
        expManager = new ExperimentManager(this);

        // schedule challenges re-publishing
        challengePublishTimer = new Timer();
        PlatformController controller = this;
        challengePublishTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                republishChallenges(storage, queue, controller);
            }
        }, PUBLISH_CHALLENGES, PUBLISH_CHALLENGES);

        LOGGER.info("Platform controller initialized.");
    }

    /**
     * Handles incoming command request from the hobbit command queue.
     * 
     * <p>
     * Commands handled by this method:
     * <ul>
     * <li>{@link Commands#DOCKER_CONTAINER_START}</li>
     * <li>{@link Commands#DOCKER_CONTAINER_STOP}</li>
     * </ul>
     *
     * @param command
     *            command to be executed
     * @param data
     *            byte-encoded supplementary json for the command
     *
     *            0 - start container 1 - stop container Data format for each
     *            command: Start container:
     */
    public void receiveCommand(byte command, byte[] data, String sessionId, String replyTo) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.info("received command: session={}, command={}, data={}", sessionId, Commands.toString(command),
                    data != null ? RabbitMQUtils.readString(data) : "null");
        } else {
            LOGGER.info("received command: session={}, command={}", sessionId, Commands.toString(command));
        }
        // This command will receive data from Rabbit
        // determine the command
        switch (command) {
        case Commands.DOCKER_CONTAINER_START: {
            // Convert data byte array to config data structure
            StartCommandData startParams = deserializeStartCommandData(data);
            // trigger creation
            String containerName = createContainer(startParams);
            if (replyTo != null) {
                try {
                    cmdChannel.basicPublish("", replyTo, MessageProperties.PERSISTENT_BASIC,
                            RabbitMQUtils.writeString(containerName));
                } catch (IOException e) {
                    StringBuilder errMsgBuilder = new StringBuilder();
                    errMsgBuilder.append("Error, couldn't sent response after creation of container (");
                    errMsgBuilder.append(startParams.toString());
                    errMsgBuilder.append(") to replyTo=");
                    errMsgBuilder.append(replyTo);
                    errMsgBuilder.append(".");
                    LOGGER.error(errMsgBuilder.toString(), e);
                }
            }
            break;
        }
        case Commands.DOCKER_CONTAINER_STOP: {
            // get containerId from params
            StopCommandData stopParams = deserializeStopCommandData(data);
            // trigger stop
            stopContainer(stopParams.containerName);
            break;
        }
        case Commands.BENCHMARK_READY_SIGNAL: {
            expManager.systemOrBenchmarkReady(false);
            break;
        }
        case Commands.SYSTEM_READY_SIGNAL: {
            expManager.systemOrBenchmarkReady(true);
            break;
        }
        case Commands.TASK_GENERATION_FINISHED: {
            expManager.taskGenFinished();
            break;
        }
        case Commands.BENCHMARK_FINISHED_SIGNAL: {
            if ((data == null) || (data.length == 0)) {
                LOGGER.error("Got no result model from the benchmark controller.");
            } else {
                Model model = RabbitMQUtils.readModel(data);
                expManager.setResultModel(model);
            }
            break;
        }
        }
    }

    private StopCommandData deserializeStopCommandData(byte[] data) {
        if (data == null) {
            return null;
        }
        String dataString = RabbitMQUtils.readString(data);
        return gson.fromJson(dataString, StopCommandData.class);
    }

    private StartCommandData deserializeStartCommandData(byte[] data) {
        if (data == null) {
            return null;
        }
        String dataString = RabbitMQUtils.readString(data);
        return gson.fromJson(dataString, StartCommandData.class);
    }

    /**
     * Creates and starts a container based on the given
     * {@link StartCommandData} instance.
     * 
     * @param data
     *            the data needed to start the container
     * @return the name of the created container
     */
    private String createContainer(StartCommandData data) {
        String parentId = containerManager.getContainerId(data.parent);
        if (parentId == null) {
            LOGGER.error("Couldn't create container because the parent \"{}\" is not known.", data.parent);
            return null;
        }
        String containerId = containerManager.startContainer(data.image, data.type, parentId, data.environmentVariables,
                null);
        if (containerId == null) {
            return null;
        } else {
            return containerManager.getContainerName(containerId);
        }
    }

    /**
     * Stops the container with the given container name.
     * 
     * @param containerName
     *            name of the container that should be stopped
     */
    public void stopContainer(String containerName) {
        String containerId = containerManager.getContainerId(containerName);
        if (containerId != null) {
            containerManager.stopContainer(containerId);
        }
    }

    @Override
    public void run() throws Exception {
        // We sleep until the controller should terminate
        terminationMutex.acquire();
    }

    @Override
    public void notifyTermination(String containerId, int exitCode) {
        LOGGER.info("Container " + containerId + " stopped with exitCode=" + exitCode);
        // Check whether this container was part of an experiment
        expManager.notifyTermination(containerId, exitCode);
        // Remove the container from the observer
        containerObserver.removedObservedContainer(containerId);
        // If we should remove all containers created by us
        if (!DEPLOY_ENV.equals(DEPLOY_ENV_TESTING)) {
            // If we remove this container, we have to make sure that there are
            // no children that are still running
            containerManager.stopParentAndChildren(containerId);
            containerManager.removeContainer(containerId);
        }
    }

    @Override
    public void close() throws IOException {
        // stop the container observer
        try {
            if (containerObserver != null) {
                containerObserver.stopObserving();
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't stop the container observer.", e);
        }
        // get all remaining containers from the container manager,
        // terminate and remove them
        try {
            List<Container> containers = containerManager.getContainers();
            for (Container c : containers) {
                containerManager.stopContainer(c.id());
                containerManager.removeContainer(c.id());
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't stop running containers.", e);
        }
        // Close the storage client
        IOUtils.closeQuietly(storage);
        // Close the queue if this is needed
        if ((queue != null) && (queue instanceof Closeable)) {
            IOUtils.closeQuietly((Closeable) queue);
        }
        if (frontEnd2Controller != null) {
            try {
                frontEnd2Controller.close();
            } catch (Exception e) {
            }
        }
        if (controller2Analysis != null) {
            try {
                controller2Analysis.close();
            } catch (Exception e) {
            }
        }

        // Close experiment manager
        IOUtils.closeQuietly(expManager);
        // Closing the super class is the last statement!
        super.close();
    }

    @Override
    public void analyzeExperiment(String uri) throws IOException {
        controller2Analysis.basicPublish("", Constants.CONTROLLER_2_ANALYSIS_QUEUE_NAME,
                MessageProperties.PERSISTENT_BASIC, RabbitMQUtils.writeString(uri));
    }

    /**
     * Sends the given command to the command queue with the given data appended
     * and using the given properties.
     * 
     * @param address
     *            address for the message
     * @param command
     *            the command that should be sent
     * @param data
     *            data that should be appended to the command
     * @param props
     *            properties that should be used for the message
     * @throws IOException
     */
    protected void sendToCmdQueue(String address, byte command, byte data[], BasicProperties props) throws IOException {
        byte sessionIdBytes[] = RabbitMQUtils.writeString(address);
        // + 5 because 4 bytes for the session ID length and 1 byte for the
        // command
        int dataLength = sessionIdBytes.length + 5;
        boolean attachData = (data != null) && (data.length > 0);
        if (attachData) {
            dataLength += data.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(dataLength);
        buffer.putInt(sessionIdBytes.length);
        buffer.put(sessionIdBytes);
        buffer.put(command);
        if (attachData) {
            buffer.put(data);
        }
        cmdChannel.basicPublish(Constants.HOBBIT_COMMAND_EXCHANGE_NAME, "", props, buffer.array());
    }

    /**
     * The controller overrides the super method because it does not need to
     * check for the leading hobbit id and delegates the command handling to the
     * {@link #receiveCommand(byte, byte[], String, String)} method.
     */
    protected void handleCmd(byte bytes[], String replyTo) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int idLength = buffer.getInt();
        byte sessionIdBytes[] = new byte[idLength];
        buffer.get(sessionIdBytes);
        String sessionId = new String(sessionIdBytes, Charsets.UTF_8);
        byte command = buffer.get();
        byte remainingData[];
        if (buffer.remaining() > 0) {
            remainingData = new byte[buffer.remaining()];
            buffer.get(remainingData);
        } else {
            remainingData = new byte[0];
        }
        receiveCommand(command, remainingData, sessionId, replyTo);
    }

    protected void handleFrontEndCmd(byte bytes[], String replyTo, BasicProperties replyProperties) {
        if (bytes.length == 0) {
            return;
        }
        byte response[] = null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            // The first byte is the command
            switch (buffer.get()) {
            case FrontEndApiCommands.LIST_CURRENT_STATUS: {
                ControllerStatus status = getStatus();
                response = RabbitMQUtils.writeString(gson.toJson(status));
                break;
            }
            case FrontEndApiCommands.LIST_AVAILABLE_BENCHMARKS: {
                response = RabbitMQUtils.writeString(gson.toJson(imageManager.getBenchmarks()));
                break;
            }
            case FrontEndApiCommands.GET_BENCHMARK_DETAILS: {
                // get benchmarkUri
                String benchmarkUri = RabbitMQUtils.readString(buffer);
                LOGGER.info("Loading details for benchmark \"{}\"", benchmarkUri);
                Model benchmarkModel = imageManager.getBenchmarkModel(benchmarkUri);
                // If the model could be found
                if (benchmarkModel != null) {
                    List<SystemMetaData> systems4Benchmark = imageManager.getSystemsForBenchmark(benchmarkModel);
                    // If there is a username based on that the systems should
                    // be filtered
                    if (buffer.hasRemaining()) {
                        String userName = RabbitMQUtils.readString(buffer);
                        LOGGER.info("Fitlering systems for user \"{}\"", userName);
                        Set<SystemMetaData> userSystems = new HashSet<SystemMetaData>(
                                imageManager.getSystemsOfUser(userName));
                        List<SystemMetaData> filteredSystems = new ArrayList<>(systems4Benchmark.size());
                        for (SystemMetaData s : systems4Benchmark) {
                            if (userSystems.contains(s)) {
                                filteredSystems.add(s);
                            }
                        }
                        systems4Benchmark = filteredSystems;
                    }
                    response = RabbitMQUtils.writeByteArrays(new byte[][] { RabbitMQUtils.writeModel(benchmarkModel),
                            RabbitMQUtils.writeString(gson.toJson(systems4Benchmark)) });
                } else {
                    LOGGER.error("Couldn't find model of benchmark \"{}\".", benchmarkUri);
                }
                break;
            }
            case FrontEndApiCommands.ADD_EXPERIMENT_CONFIGURATION: {
                // get the benchmark URI
                String benchmarkUri = RabbitMQUtils.readString(buffer);
                String systemUri = RabbitMQUtils.readString(buffer);
                String serializedBenchParams = RabbitMQUtils.readString(buffer);
                String experimentId = addExperimentToQueue(benchmarkUri, systemUri, serializedBenchParams, null, null,
                        null);
                response = RabbitMQUtils.writeString(experimentId);
                break;
            }
            case FrontEndApiCommands.GET_SYSTEMS_OF_USER: {
                // get the user name
                String userName = RabbitMQUtils.readString(buffer);
                LOGGER.info("Loading systems of user \"{}\"", userName);
                response = RabbitMQUtils.writeString(gson.toJson(imageManager.getSystemsOfUser(userName)));
                break;
            }
            case FrontEndApiCommands.CLOSE_CHALLENGE: {
                // get the challenge URI
                String challengeUri = RabbitMQUtils.readString(buffer);
                closeChallenge(challengeUri);
                break;
            }
            default: {
                LOGGER.error("Got a request from the front end with an unknown command code {}. It will be ignored.",
                        bytes[0]);
                break;
            }
            }
        } catch (Exception e) {
            LOGGER.error("Exception while hadling front end request.", e);
        } finally {
            if (replyTo != null) {
                LOGGER.info("Replying to " + replyTo);
                try {
                    frontEnd2Controller.basicPublish("", replyTo, replyProperties,
                            response != null ? response : new byte[0]);
                } catch (IOException e) {
                    LOGGER.error("Exception while trying to send response to the front end.", e);
                }
            }
        }
        LOGGER.info("Finished handling of front end request.");
    }

    private Model getChallengeFromUri(String challengeUri) {
        // get experiments from the challenge
        String query = SparqlQueries.getChallengeGraphQuery(challengeUri, Constants.CHALLENGE_DEFINITION_GRAPH_URI);
        if (query == null) {
            LOGGER.error("Couldn't get challenge {} because the needed SPARQL query couldn't be loaded. Aborting.",
                    challengeUri);
            return null;
        }
        return storage.sendConstructQuery(query);
    }

    private List<ExperimentConfiguration> getChallengeTasksFromUri(String challengeUri) {
        Model model = getChallengeFromUri(challengeUri);
        if (model == null) {
            LOGGER.error("Couldn't get model for challenge {} . Aborting.", challengeUri);
            return null;
        }
        Resource challengeResource = model.getResource(challengeUri);
        Calendar executionDate = RdfHelper.getDateValue(model, challengeResource, HOBBIT.executionDate);
        ResIterator taskIterator = model.listSubjectsWithProperty(HOBBIT.isTaskOf, challengeResource);
        List<ExperimentConfiguration> experiments = new ArrayList<>();
        while (taskIterator.hasNext()) {
            Resource challengeTask = taskIterator.next();
            String challengeTaskUri = challengeTask.getURI();
            // get benchmark information
            String benchmarkUri = RdfHelper.getStringValue(model, challengeTask, HOBBIT.involvesBenchmark);
            String experimentId, systemUri, serializedBenchParams;
            // iterate participating system instances
            NodeIterator systemInstanceIterator = model.listObjectsOfProperty(challengeTask,
                    HOBBIT.involvesSystemInstance);
            RDFNode sysInstance;
            while (systemInstanceIterator.hasNext()) {
                sysInstance = systemInstanceIterator.next();
                if (sysInstance.isURIResource()) {
                    systemUri = sysInstance.asResource().getURI();
                    experimentId = generateExperimentId();
                    serializedBenchParams = RabbitMQUtils
                            .writeModel2String(createExpModelForChallengeTask(model, challengeTaskUri, systemUri));
                    experiments.add(new ExperimentConfiguration(experimentId, benchmarkUri, serializedBenchParams,
                            systemUri, challengeUri, challengeTaskUri, executionDate));
                } else {
                    LOGGER.error("Couldn't get the benchmark for challenge task \"{}\". This task will be ignored.",
                            challengeTaskUri);
                }
            }
        }
        return experiments;
    }

    /**
     * Closes the challenge with the given URI by adding the "closed" triple to
     * its graph and inserting the configured experiments into the queue.
     *
     * @param challengeUri
     *            the URI of the challenge that should be closed
     */
    private void closeChallenge(String challengeUri) {
        // send SPARQL query to close the challenge
        String query = SparqlQueries.getCloseChallengeQuery(challengeUri, Constants.CHALLENGE_DEFINITION_GRAPH_URI);
        if (query == null) {
            LOGGER.error(
                    "Couldn't close the challenge {} because the needed SPARQL query couldn't be loaded. Aborting.",
                    challengeUri);
            return;
        }
        if (!storage.sendUpdateQuery(query)) {
            LOGGER.error("Couldn't close the challenge {} because the SPARQL query didn't had any effect. Aborting.",
                    challengeUri);
            return;
        }
        // get experiments from the challenge
        List<ExperimentConfiguration> experiments = getChallengeTasksFromUri(challengeUri);
        if (experiments == null) {
            LOGGER.error("Couldn't get experiments for challenge {} . Aborting.", challengeUri);
            return;
        }
        // add to queue
        for (ExperimentConfiguration ex : experiments) {
            LOGGER.info("Adding experiment " + ex.id + " with benchmark " + ex.benchmarkUri + " and system "
                    + ex.systemUri + " to the queue.");
            queue.add(ex);
        }
    }

    /*
     * The method is static for an easier JUnit test implementation
     */
    protected synchronized static void republishChallenges(StorageServiceClient storage, ExperimentQueue queue,
            ExperimentAnalyzer analyzer) {
        LOGGER.info("Checking for challenges to publish...");
        // Get list of all UNPUBLISHED, closed challenges, their tasks and
        // publication dates
        Model challengesModel = storage.sendConstructQuery(
                SparqlQueries.getChallengePublishInfoQuery(null, Constants.CHALLENGE_DEFINITION_GRAPH_URI));
        ResIterator challengeIterator = challengesModel.listResourcesWithProperty(RDF.type, HOBBIT.Challenge);
        Resource challenge;
        Calendar now = Calendar.getInstance(Constants.DEFAULT_TIME_ZONE);
        // go through the challenges
        while (challengeIterator.hasNext()) {
            challenge = challengeIterator.next();
            Calendar publishDate = RdfHelper.getDateTimeValue(challengesModel, challenge, HOBBIT.publicationDate);
            if (publishDate == null) {
                publishDate = RdfHelper.getDateValue(challengesModel, challenge, HOBBIT.publicationDate);
            }
            // If the challenge results should be published
            if ((publishDate != null) && (now.after(publishDate))) {
                List<Resource> taskResources = RdfHelper.getSubjectResources(challengesModel, HOBBIT.isTaskOf,
                        challenge);
                Set<String> tasks = new HashSet<>();
                for (Resource taskResource : taskResources) {
                    tasks.add(taskResource.getURI());
                }
                /*
                 * Check that all experiments that belong to the challenge have
                 * been finished. Note that we don't have to check the
                 * experiment that is running at the moment, since it is part of
                 * the queue.
                 */
                int count = 0;
                for (ExperimentConfiguration config : queue.listAll()) {
                    if (tasks.contains(config.challengeTaskUri)) {
                        ++count;
                    }
                }
                // if there are no challenge experiments in the queue
                if (count == 0) {
                    LOGGER.info("publishing challenge {}", challenge.getURI());
                    // get the challenge model
                    Model challengeModel = storage.sendConstructQuery(SparqlQueries
                            .getChallengeGraphQuery(challenge.getURI(), Constants.CHALLENGE_DEFINITION_GRAPH_URI));
                    // insert the challenge into the public graph
                    if (!storage.sendInsertQuery(challengeModel, Constants.PUBLIC_RESULT_GRAPH_URI)) {
                        LOGGER.error("Couldn't copy the graph of the challenge \"{}\". Aborting.", challenge.getURI());
                        return;
                    }
                    // copy the results to the public result graph
                    List<Resource> experiments = new ArrayList<>();
                    for (String taskUri : tasks) {
                        Model taskExperimentModel = storage.sendConstructQuery(SparqlQueries
                                .getExperimentOfTaskQuery(null, taskUri, Constants.PRIVATE_RESULT_GRAPH_URI));
                        experiments.addAll(RdfHelper.getSubjectResources(taskExperimentModel, HOBBIT.isPartOf,
                                taskExperimentModel.getResource(taskUri)));
                        try {
                            analyzer.analyzeExperiment(taskUri);
                        } catch (IOException e) {
                            LOGGER.error("Could not send task \"{}\" to AnalyseQueue.", taskUri);
                        }
                        if (!storage.sendInsertQuery(taskExperimentModel, Constants.PUBLIC_RESULT_GRAPH_URI)) {
                            LOGGER.error("Couldn't copy experiment results for challenge task \"{}\". Aborting.",
                                    taskUri);
                            return;
                        }
                    }
                    // Remove challenge from challenge graph
                    storage.sendUpdateQuery(SparqlQueries.deleteChallengeGraphQuery(challenge.getURI(),
                            Constants.CHALLENGE_DEFINITION_GRAPH_URI));
                    // Remove experiments
                    for (Resource experiment : experiments) {
                        storage.sendUpdateQuery(SparqlQueries.deleteExperimentGraphQuery(experiment.getURI(),
                                Constants.PRIVATE_RESULT_GRAPH_URI));
                    }
                    // Clean up the remaining graph
                    String queries[] = SparqlQueries
                            .cleanUpChallengeGraphQueries(Constants.CHALLENGE_DEFINITION_GRAPH_URI);
                    for (int i = 0; i < queries.length; ++i) {
                        storage.sendUpdateQuery(queries[i]);
                    }
                    queries = SparqlQueries.cleanUpPrivateGraphQueries(Constants.PRIVATE_RESULT_GRAPH_URI);
                    for (int i = 0; i < queries.length; ++i) {
                        storage.sendUpdateQuery(queries[i]);
                    }
                }
            }
        }
    }

    private Model createExpModelForChallengeTask(Model model, String challengeTaskUri, String systemUri) {
        String query = SparqlQueries.getCreateExperimentFromTaskQuery(Constants.NEW_EXPERIMENT_URI, challengeTaskUri,
                systemUri, null);
        if (query == null) {
            LOGGER.error("Couldn't load SPARQL query to create an RDF model for a new experiment. Returning null.");
            return null;
        }
        QueryExecution qe = QueryExecutionFactory.create(query, model);
        return qe.execConstruct();
    }

    /**
     * Adds a new experiment with the given benchmark, system and benchmark
     * parameter to the queue.
     * 
     * @param benchmarkUri
     *            the URI of the benchmark
     * @param systemUri
     *            the URI of the system
     * @param serializedBenchParams
     *            the serialized benchmark parameters
     * @param executionDate
     *            the date at which this experiment should be executed as part
     *            of a challenge. Should be set to <code>null</code> if it is
     *            not part of a challenge.
     * @return the Id of the created experiment
     */
    protected String addExperimentToQueue(String benchmarkUri, String systemUri, String serializedBenchParams,
            String challengUri, String challengTaskUri, Calendar executionDate) {
        String experimentId = generateExperimentId();
        LOGGER.info("Adding experiment " + experimentId + " with benchmark " + benchmarkUri + " and system " + systemUri
                + " to the queue.");
        queue.add(new ExperimentConfiguration(experimentId, benchmarkUri, serializedBenchParams, systemUri, challengUri,
                challengTaskUri, executionDate));
        return experimentId;
    }

    /**
     * Creates a status object summarizing the current status of this
     * controller.
     * 
     * @return the status of this controller
     */
    private ControllerStatus getStatus() {
        ControllerStatus status = new ControllerStatus();
        expManager.addStatusInfo(status);
        if (status.currentSystemUri != null) {
            Model model = imageManager.getSystemModel(status.currentSystemUri);
            if (model != null) {
                status.currentSystemName = RdfHelper.getLabel(model, model.getResource(status.currentSystemUri));
            }
        }
        List<ExperimentConfiguration> experiments = queue.listAll();
        List<ConfiguredExperiment> tempQueue = new ArrayList<ConfiguredExperiment>(experiments.size());
        ConfiguredExperiment queuedExp;
        for (ExperimentConfiguration experiment : experiments) {
            if (!experiment.id.equals(status.currentExperimentId)) {
                queuedExp = new ConfiguredExperiment();
                queuedExp.benchmarkUri = experiment.benchmarkUri;
                queuedExp.systemUri = experiment.systemUri;
                tempQueue.add(queuedExp);
            }
        }
        status.queue = tempQueue.toArray(new ConfiguredExperiment[tempQueue.size()]);
        return status;
    }

    /**
     * Generates a unique experiment Id based on the current time stamp and the
     * last Id ({@link #lastIdTime}) that has been created.
     * 
     * @return a unique experiment Id
     */
    private synchronized String generateExperimentId() {
        long time = System.currentTimeMillis();
        while (time <= lastIdTime) {
            ++time;
        }
        lastIdTime = time;
        return Long.toString(time);
    }

    /**
     * Generates an experiment URI using the given id and the experiment URI
     * namespace defined by {@link Constants#EXPERIMENT_URI_NS}.
     * 
     * @param id
     *            the id of the experiment
     * @return the experiment URI
     */
    public static String generateExperimentUri(String id) {
        return Constants.EXPERIMENT_URI_NS + id;
    }

    public ImageManager imageManager() {
        return imageManager;
    }

    public StorageServiceClient storage() {
        return storage;
    }

    public String rabbitMQHostName() {
        return rabbitMQHostName;
    }

    ///// There are some methods that shouldn't be used by the controller and
    ///// have been marked as deprecated

    /**
     * @deprecated Not used inside the controller. Use
     *             {@link #receiveCommand(byte, byte[], String, String)}
     *             instead.
     */
    @Deprecated
    @Override
    public void receiveCommand(byte command, byte[] data) {
    }

    /**
     * @deprecated The {@link PlatformController} should use
     *             {@link #sendToCmdQueue(String, byte, byte[], BasicProperties)}
     */
    @Deprecated
    protected void sendToCmdQueue(byte command) throws IOException {
        sendToCmdQueue(Constants.HOBBIT_SESSION_ID_FOR_PLATFORM_COMPONENTS, command, null, null);
    }

    /**
     * @deprecated The {@link PlatformController} should use
     *             {@link #sendToCmdQueue(String, byte, byte[], BasicProperties)}
     */
    @Deprecated
    protected void sendToCmdQueue(byte command, byte data[]) throws IOException {
        sendToCmdQueue(Constants.HOBBIT_SESSION_ID_FOR_PLATFORM_COMPONENTS, command, data, null);
    }

    /**
     * @deprecated The {@link PlatformController} should use
     *             {@link #sendToCmdQueue(String, byte, byte[], BasicProperties)}
     */
    @Deprecated
    protected void sendToCmdQueue(byte command, byte data[], BasicProperties props) throws IOException {
        sendToCmdQueue(Constants.HOBBIT_SESSION_ID_FOR_PLATFORM_COMPONENTS, command, data, props);
    }
}