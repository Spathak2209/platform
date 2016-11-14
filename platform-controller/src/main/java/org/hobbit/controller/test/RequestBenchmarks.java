package org.hobbit.controller.test;

import java.io.IOException;
import java.util.Collection;

import org.hobbit.core.Constants;
import org.hobbit.core.FrontEndApiCommands;
import org.hobbit.core.components.AbstractCommandReceivingComponent;
import org.hobbit.core.data.BenchmarkMetaData;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.QueueingConsumer;

public class RequestBenchmarks extends AbstractCommandReceivingComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBenchmarks.class);

    private static final long REQUEST_TIMEOUT = 60000;
    
    private static final String NEWLINE = String.format("%n%n");

    protected Channel frontEnd2Controller;
    protected QueueingConsumer consumer;

    @Override
    public void init() throws Exception {
        super.init();

        frontEnd2Controller = connection.createChannel();
        frontEnd2Controller.queueDeclare(Constants.FRONT_END_2_CONTROLLER_QUEUE_NAME, false, false, true, null);

        frontEnd2Controller.queueDeclare(Constants.CONTROLLER_2_FRONT_END_QUEUE_NAME, false, false, true, null);

        consumer = new QueueingConsumer(frontEnd2Controller);
        frontEnd2Controller.basicConsume(Constants.CONTROLLER_2_FRONT_END_QUEUE_NAME, true, consumer);
    }

    @Override
    public void receiveCommand(byte command, byte[] data) {
        // nothing to do
    }

    @Override
    public void run() throws Exception {
        LOGGER.info("Sending request...");
        BasicProperties props = new BasicProperties.Builder().deliveryMode(2)
                .replyTo(Constants.CONTROLLER_2_FRONT_END_QUEUE_NAME).build();
        frontEnd2Controller.basicPublish("", Constants.FRONT_END_2_CONTROLLER_QUEUE_NAME, props,
                new byte[] { FrontEndApiCommands.LIST_AVAILABLE_BENCHMARKS });
        LOGGER.info("Waiting for response...");
        QueueingConsumer.Delivery delivery = consumer.nextDelivery(REQUEST_TIMEOUT);
        if (delivery == null) {
            throw new IOException("Didn't got a response after \"" + REQUEST_TIMEOUT + "\" ms.");
        }
        // parse the response
        String jsonString = RabbitMQUtils.readString(delivery.getBody());
        Gson gson = new Gson();
        Collection<BenchmarkMetaData> benchmarks = gson.fromJson(jsonString,
                new TypeToken<Collection<BenchmarkMetaData>>() {
                }.getType());
        // print results
        StringBuilder builder = new StringBuilder();
        builder.append("Response:");
        builder.append(NEWLINE);
        builder.append("benchmarks:");
        for (BenchmarkMetaData benchmark : benchmarks) {
            builder.append(NEWLINE);
            builder.append("\tname: ");
            builder.append(benchmark.benchmarkName);
            builder.append(NEWLINE);
            builder.append("\turi: ");
            builder.append(benchmark.benchmarkUri);
            builder.append(NEWLINE);
            builder.append("\tdescription: ");
            builder.append(benchmark.benchmarkDescription);
            builder.append(NEWLINE);
        }
        LOGGER.info(builder.toString());
    }

    @Override
    public void close() throws IOException {
        if (frontEnd2Controller != null) {
            try {
                frontEnd2Controller.close();
            } catch (Exception e) {
            }
        }
        super.close();
    }

}
