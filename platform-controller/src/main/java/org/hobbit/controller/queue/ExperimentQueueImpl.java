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
package org.hobbit.controller.queue;

import java.io.Closeable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.hobbit.controller.data.ExperimentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.sync.RedisCommands;

/**
 * Created by Timofey Ermilov on 07/09/16. TODO: 1. Setup Redis-based queue for
 * normal experiments 2. Setup Redis-based queue for challenge experiments 3.
 * Way to get next task in queue, prioritise challenges if challenge.startTime =
 * now
 */
public class ExperimentQueueImpl implements ExperimentQueue, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentQueueImpl.class);
    
    public final static String CHALLENGE_KEY = "challenge";
    public final static String EXPERIMENT_KEY = "experiment";
    public final static String CHALLENGE_QUEUE = "challenge_queue";
    public final static String EXPERIMENT_QUEUE = "experiment_queue";

    // redis connection
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redisSyncCommands;

    public ExperimentQueueImpl() {
        // init redis redisConnection
        String host = "redis://localhost";
        if (System.getenv().containsKey("HOBBIT_REDIS_HOST")) {
            host = "redis://" + System.getenv().get("HOBBIT_REDIS_HOST");
        }
        redisClient = RedisClient.create(host);
        redisConnection = redisClient.connect();
        redisSyncCommands = redisConnection.sync();
    }

    private ExperimentConfiguration decodeExperimentFromString(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, ExperimentConfiguration.class);
    }

    @Override
    public ExperimentConfiguration getNextExperiment() {
        String timestamp = Long.toString(new Timestamp(System.currentTimeMillis()).getTime());
        List<String> experimentIds = redisSyncCommands.zrangebyscore(EXPERIMENT_QUEUE, "-inf", timestamp);
        List<String> challengeIds = redisSyncCommands.zrangebyscore(CHALLENGE_QUEUE, "-inf", timestamp);
        if (experimentIds.isEmpty() && challengeIds.isEmpty()) {
            return null;
        }

        ExperimentConfiguration experiment = null;
        ExperimentConfiguration challenge = null;

        if (!experimentIds.isEmpty()) {
            String experimentId = experimentIds.get(0);
            String experimentStr = redisSyncCommands.hget(EXPERIMENT_KEY, experimentId);
            experiment = decodeExperimentFromString(experimentStr);
        }

        if (!challengeIds.isEmpty()) {
            String challengeId = challengeIds.get(0);
            String challengeStr = redisSyncCommands.hget(CHALLENGE_KEY, challengeId);
            challenge = decodeExperimentFromString(challengeStr);
        }

        // If a challenge experiment is available
        if (challenge != null) {
            // If the experiment is faulty (i.e., it has no execution date defined)
            if (challenge.executionDate == null) {
                LOGGER.error(
                        "Got a challenge experiment without an execution date. Setting the execution date to 'now' and returning it.");
                challenge.executionDate = Calendar.getInstance();
                return challenge;
                // If we reached the point in time to execute this experiment
            } else if (challenge.executionDate.before(Calendar.getInstance())) {
                return challenge;
            }
        }
        return experiment;
    }

    @Override
    public void add(ExperimentConfiguration experiment){
        Gson gson = new Gson();
        String typeKey, queueKey;
        if ((experiment.challengeUri != null) && (experiment.challengeTaskUri != null)) {
            typeKey = CHALLENGE_KEY;
            queueKey = CHALLENGE_QUEUE;
        } else {
            typeKey = EXPERIMENT_KEY;
            queueKey = EXPERIMENT_QUEUE;
        }
        String idKey = experiment.id;
        String experimentJson = gson.toJson(experiment);

        // add to experiment data store
        redisSyncCommands.hset(typeKey, idKey, experimentJson);
        long timestamp = 0;
        if (experiment.executionDate != null) {
            timestamp = experiment.executionDate.getTimeInMillis();
        }
        // append it to queue
        redisSyncCommands.zadd(queueKey, (double) timestamp, idKey);
    }

    @Override
    public boolean remove(ExperimentConfiguration experiment) {
        String typeKey, queueKey;
        if ((experiment.challengeUri != null) && (experiment.challengeTaskUri != null)) {
            typeKey = CHALLENGE_KEY;
            queueKey = CHALLENGE_QUEUE;
        } else {
            typeKey = EXPERIMENT_KEY;
            queueKey = EXPERIMENT_QUEUE;
        }
        String idKey = experiment.id;
        // remove from experiment data store
        long removedFields1 = redisSyncCommands.hdel(typeKey, idKey);
        // remove from queue
        long removedFields2 = redisSyncCommands.zrem(queueKey, idKey);
        return (removedFields1 > 0) && (removedFields2 > 0);
    }

    private List<ExperimentConfiguration> stringMapToExperimentList(Map<String, String> entries) {
        List<ExperimentConfiguration> result = new ArrayList<>();
        // decode and append all entries to result
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            ExperimentConfiguration c = decodeExperimentFromString(entry.getValue());
            result.add(c);
        }
        return result;
    }

    @Override
    public List<ExperimentConfiguration> listAll() {
        // get all experiments
        Map<String, String> experiments = redisSyncCommands.hgetall(EXPERIMENT_KEY);
        // create result
        List<ExperimentConfiguration> result = stringMapToExperimentList(experiments);
        // result.sort((ExperimentConfiguration o1, ExperimentConfiguration o2) ->
        // o1.executionDate == null
        // ? (o2.executionDate == null ? 0 : 1)
        // : (o2.executionDate == null ? -1 : (o1.executionDate.before(o2.executionDate)
        // ? -1 : 1)));
        Map<String, String> challenges = redisSyncCommands.hgetall(CHALLENGE_KEY);
        // Add all challenges in front of the experiments
        result.addAll(0, stringMapToExperimentList(challenges));
        // return result
        return result;
    }

    @Override
    public ExperimentConfiguration getExperiment(String experimentId) {
        String experimentStr = redisSyncCommands.hget(EXPERIMENT_KEY, experimentId);
        if (experimentStr == null) {
            experimentStr = redisSyncCommands.hget(CHALLENGE_KEY, experimentId);
        }
        if (experimentStr != null) {
            return decodeExperimentFromString(experimentStr);
        } else {
            return null;
        }
    }

    public void close() {
        redisConnection.close();
        redisClient.shutdown();
    }
}
