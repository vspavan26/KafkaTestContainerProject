package org.testcontainers.containers;

import static java.util.Collections.singletonList;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Assert;
import org.junit.Test;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;
import org.testcontainers.utility.DockerImageName;

public class KafkaContainerTest {

    private static final DockerImageName KAFKA_TEST_IMAGE = DockerImageName.parse("confluentinc/cp-kafka:5.4.3");

    @Test
    public void testUsage() throws Exception {
        KafkaContainer kafka = new KafkaContainer(KAFKA_TEST_IMAGE);
        try {
            kafka.start();
            testKafkaFunctionality(kafka.getBootstrapServers());
        }
        finally {
            kafka.stop();
        }
    }

    protected void testKafkaFunctionality(String bootstrapServers) throws Exception {
        testKafkaFunctionality(bootstrapServers, 1, 1);
    }

    protected void testKafkaFunctionality(String bootstrapServers, int partitions, int rf) throws Exception {
        try (

                //Create an adminClient which helps us to create a topic
                AdminClient adminClient = AdminClient.create(ImmutableMap.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers
                ));


                //Create a KAFKA producer
                KafkaProducer<String, String> producer = new KafkaProducer<>(
                        ImmutableMap.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                                ProducerConfig.CLIENT_ID_CONFIG, UUID.randomUUID().toString()
                        ),
                        new StringSerializer(),
                        new StringSerializer()
                );

                //Create a KAFKA Consumer
                KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                        ImmutableMap.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                                ConsumerConfig.GROUP_ID_CONFIG, "tc-" + UUID.randomUUID(),
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
                        ),
                        new StringDeserializer(),
                        new StringDeserializer()
                );
        ) {
            String topicName = "ASXTopic";

            Collection<NewTopic> topics = singletonList(new NewTopic(topicName, partitions, (short) rf));
            adminClient.createTopics(topics).all().get(30, TimeUnit.SECONDS);

            //Ensure that the consumer subscribes to the topic before the producer sends the message
            consumer.subscribe(singletonList(topicName));


            //Message sent by producer
            producer.send(new ProducerRecord<>(topicName, "testcontainers", "Hello World!")).get();

            Unreliables.retryUntilTrue(10, TimeUnit.SECONDS, () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                if (records.isEmpty()) {
                    return false;
                }


                //Validate message sent in topic
                for (ConsumerRecord<String, String> record : records){
                    Assert.assertEquals("Validate topic",record.topic(),"ASXTopic");
                    Assert.assertEquals("Validate value",record.value(),"Hello World!");
                }


                return true;
            });

            //Unsubscribe once message is received.
            consumer.unsubscribe();
        }
    }

}