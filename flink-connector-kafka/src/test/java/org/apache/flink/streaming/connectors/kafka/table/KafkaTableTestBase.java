/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.kafka.table;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.kafka.testutils.KafkaUtil;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.test.util.AbstractTestBase;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

/** Base class for Kafka Table IT Cases. */
@Testcontainers
abstract class KafkaTableTestBase extends AbstractTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTableTestBase.class);

    private static final String INTER_CONTAINER_KAFKA_ALIAS = "kafka";
    private static final int zkTimeoutMills = 30000;

    @Container
    public static final KafkaContainer KAFKA_CONTAINER =
            KafkaUtil.createKafkaContainer(KafkaTableTestBase.class)
                    .withEmbeddedZookeeper()
                    .withNetworkAliases(INTER_CONTAINER_KAFKA_ALIAS)
                    .withEnv(
                            "KAFKA_TRANSACTION_MAX_TIMEOUT_MS",
                            String.valueOf(Duration.ofHours(2).toMillis()))
                    // Disable log deletion to prevent records from being deleted during test run
                    .withEnv("KAFKA_LOG_RETENTION_MS", "-1");

    protected StreamExecutionEnvironment env;
    protected StreamTableEnvironment tEnv;

    // Timer for scheduling logging task if the test hangs
    private final Timer loggingTimer = new Timer("Debug Logging Timer");

    @BeforeEach
    void setup() {
        Configuration configuration = new Configuration();
        configuration.set(RestartStrategyOptions.RESTART_STRATEGY, "disable");
        env = StreamExecutionEnvironment.getExecutionEnvironment(configuration);
        tEnv = StreamTableEnvironment.create(env);

        // Probe Kafka broker status per 30 seconds
        scheduleTimeoutLogger(
                Duration.ofSeconds(30),
                () -> {
                    // List all non-internal topics
                    final Map<String, TopicDescription> topicDescriptions =
                            describeExternalTopics();
                    LOG.info("Current existing topics: {}", topicDescriptions.keySet());

                    // Log status of topics
                    logTopicPartitionStatus(topicDescriptions);
                });
    }

    @AfterEach
    void after() {
        // Cancel timer for debug logging
        cancelTimeoutLogger();
    }

    public Properties getStandardProps() {
        Properties standardProps = new Properties();
        standardProps.put("bootstrap.servers", KAFKA_CONTAINER.getBootstrapServers());
        standardProps.put("group.id", "flink-tests");
        standardProps.put("enable.auto.commit", false);
        standardProps.put("auto.offset.reset", "earliest");
        standardProps.put("max.partition.fetch.bytes", 256);
        standardProps.put("zookeeper.session.timeout.ms", zkTimeoutMills);
        standardProps.put("zookeeper.connection.timeout.ms", zkTimeoutMills);
        return standardProps;
    }

    public String getBootstrapServers() {
        return KAFKA_CONTAINER.getBootstrapServers();
    }

    public void createTestTopic(String topic, int numPartitions, int replicationFactor) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            admin.createTopics(
                            Collections.singletonList(
                                    new NewTopic(topic, numPartitions, (short) replicationFactor)))
                    .all()
                    .get();
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format(
                            "Fail to create topic [%s partitions: %d replication factor: %d].",
                            topic, numPartitions, replicationFactor),
                    e);
        }
    }

    public Map<TopicPartition, OffsetAndMetadata> getConsumerOffset(String groupId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            ListConsumerGroupOffsetsResult result = admin.listConsumerGroupOffsets(groupId);
            return result.partitionsToOffsetAndMetadata().get();
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format("Fail to get consumer offsets with the group id [%s].", groupId),
                    e);
        }
    }

    public void deleteTestTopic(String topic) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            admin.deleteTopics(Collections.singletonList(topic)).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Fail to delete topic [%s].", topic), e);
        }
    }

    public void deleteRecords(String topic, Map<Integer, Long> partitionOffsetsToDelete) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        try (AdminClient admin = AdminClient.create(properties)) {
            Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
            for (Map.Entry<Integer, Long> entry : partitionOffsetsToDelete.entrySet()) {
                TopicPartition partition = new TopicPartition(topic, entry.getKey());
                RecordsToDelete records = RecordsToDelete.beforeOffset(entry.getValue());
                recordsToDelete.put(partition, records);
            }
            admin.deleteRecords(recordsToDelete).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format("Fail to delete records on topic [%s].", topic), e);
        }
    }

    // ------------------------ For Debug Logging Purpose ----------------------------------

    private void scheduleTimeoutLogger(Duration period, Runnable loggingAction) {
        TimerTask timeoutLoggerTask =
                new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            loggingAction.run();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to execute logging action", e);
                        }
                    }
                };
        loggingTimer.schedule(timeoutLoggerTask, 0L, period.toMillis());
    }

    private void cancelTimeoutLogger() {
        loggingTimer.cancel();
    }

    private Map<String, TopicDescription> describeExternalTopics() {
        try (final AdminClient adminClient = AdminClient.create(getStandardProps())) {
            final List<String> topics =
                    adminClient.listTopics().listings().get().stream()
                            .filter(listing -> !listing.isInternal())
                            .map(TopicListing::name)
                            .collect(Collectors.toList());

            return adminClient.describeTopics(topics).allTopicNames().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to list Kafka topics", e);
        }
    }

    private void logTopicPartitionStatus(Map<String, TopicDescription> topicDescriptions) {
        final Properties properties = getStandardProps();
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "flink-tests-debugging");
        properties.setProperty(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getCanonicalName());
        properties.setProperty(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getCanonicalName());
        final KafkaConsumer<?, ?> consumer = new KafkaConsumer<String, String>(properties);
        List<TopicPartition> partitions = new ArrayList<>();
        topicDescriptions.forEach(
                (topic, description) ->
                        description
                                .partitions()
                                .forEach(
                                        tpInfo ->
                                                partitions.add(
                                                        new TopicPartition(
                                                                topic, tpInfo.partition()))));
        final Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
        final Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        partitions.forEach(
                partition ->
                        LOG.info(
                                "TopicPartition \"{}\": starting offset: {}, stopping offset: {}",
                                partition,
                                beginningOffsets.get(partition),
                                endOffsets.get(partition)));
    }
}
