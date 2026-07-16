package com.google.adk.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.kafka.model.KafkaEventData;
import com.google.adk.kafka.repository.KafkaEventRepository;
import com.google.adk.utils.PropertiesHelper;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka consumer service for processing adk events. This service reads events from Kafka and
 * persists them to PostgreSQL database.
 *
 * <p>The service provides lifecycle management with start/stop capabilities and runs the consumer
 * in a separate thread for non-blocking operation.
 */
public class KafkaConsumerService {

  private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerService.class);
  private static final String ADK_EVENT = "adk-event";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final KafkaEventRepository eventRepository = new KafkaEventRepository();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean shouldStop = new AtomicBoolean(false);

  private Consumer<String, String> consumer;
  private Thread consumerThread;

  // Configuration properties
  private String kafkaBrokerAddress;
  private String topicName;
  private String consumerGroup;

  /** Initialize the Kafka consumer service with configuration from PropertiesHelper. */
  public KafkaConsumerService() {
    loadConfiguration();
  }

  /**
   * Initialize the Kafka consumer service with custom configuration.
   *
   * @param kafkaBrokerAddress Kafka broker address
   * @param topicName Topic name to consume from
   * @param consumerGroup Consumer group name
   */
  public KafkaConsumerService(String kafkaBrokerAddress, String topicName, String consumerGroup) {
    this.kafkaBrokerAddress = kafkaBrokerAddress;
    this.topicName = topicName;
    this.consumerGroup = consumerGroup;
  }

  /** Load configuration from PropertiesHelper. */
  private void loadConfiguration() {
    try {
      kafkaBrokerAddress = PropertiesHelper.getInstance().getValue("kafkaBrokerAddress");
      topicName = PropertiesHelper.getInstance().getValue("kafka_topic");
      consumerGroup = PropertiesHelper.getInstance().getValue("kafka_consumer_group");

      // Set defaults if not found
      if (kafkaBrokerAddress == null || kafkaBrokerAddress.trim().isEmpty()) {
        kafkaBrokerAddress = "localhost:9092";
        logger.warn(
            "kafkaBrokerAddress not found in config, using default: {}", kafkaBrokerAddress);
      }

      if (topicName == null || topicName.trim().isEmpty()) {
        topicName = "adk-event";
        logger.warn("kafka_topic not found in config, using default: {}", topicName);
      }

      if (consumerGroup == null || consumerGroup.trim().isEmpty()) {
        consumerGroup = "adk-event-consumer-group";
        logger.warn("kafka_consumer_group not found in config, using default: {}", consumerGroup);
      }

      logger.info(
          "Kafka consumer configuration loaded - Broker: {}, Topic: {}, Group: {}",
          kafkaBrokerAddress,
          topicName,
          consumerGroup);

    } catch (Exception e) {
      logger.error("Error loading Kafka consumer configuration: {}", e.getMessage());
      throw new RuntimeException("Failed to load Kafka consumer configuration", e);
    }
  }

  /**
   * Create and configure the Kafka consumer.
   *
   * @return Configured Kafka consumer
   */
  private Consumer<String, String> createConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokerAddress);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    // Consumer behavior settings
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual commit
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
    props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
    props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

    // Performance settings
    props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
    props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

    Consumer<String, String> consumer = new KafkaConsumer<>(props);
    consumer.subscribe(Collections.singletonList(topicName));

    logger.info("Kafka consumer created and subscribed to topic: {}", topicName);
    return consumer;
  }

  /**
   * Start the Kafka consumer service. This method is thread-safe and can be called multiple times.
   */
  public synchronized void start() {
    if (running.get()) {
      logger.warn("Kafka consumer service is already running");
      return;
    }

    try {
      consumer = createConsumer();
      shouldStop.set(false);
      running.set(true);

      consumerThread = new Thread(this::runConsumerLoop, "kafka-consumer-thread");
      consumerThread.start();

      logger.info("Kafka consumer service started successfully");

    } catch (Exception e) {
      logger.error("Failed to start Kafka consumer service: {}", e.getMessage());
      running.set(false);
      throw new RuntimeException("Failed to start Kafka consumer service", e);
    }
  }

  /**
   * Stop the Kafka consumer service gracefully. This method is thread-safe and can be called
   * multiple times.
   */
  public synchronized void stop() {
    if (!running.get()) {
      logger.warn("Kafka consumer service is not running");
      return;
    }

    try {
      logger.info("Stopping Kafka consumer service...");
      shouldStop.set(true);

      // Wait for consumer thread to finish (with timeout)
      if (consumerThread != null && consumerThread.isAlive()) {
        consumerThread.join(10000); // 10 second timeout
        if (consumerThread.isAlive()) {
          logger.warn("Consumer thread did not stop gracefully, interrupting...");
          consumerThread.interrupt();
        }
      }

      // Close the consumer
      if (consumer != null) {
        consumer.close();
      }

      running.set(false);
      logger.info("Kafka consumer service stopped successfully");

    } catch (Exception e) {
      logger.error("Error stopping Kafka consumer service: {}", e.getMessage());
    }
  }

  /**
   * Check if the service is currently running.
   *
   * @return true if running, false otherwise
   */
  public boolean isRunning() {
    return running.get();
  }

  /**
   * Main consumer loop that processes messages from Kafka. This method runs in a separate thread.
   */
  private void runConsumerLoop() {
    logger.info("Starting Kafka consumer loop");

    try {
      while (!shouldStop.get()) {
        try {
          // Poll for messages with timeout
          ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

          if (records.isEmpty()) {
            continue; // No messages, continue polling
          }

          logger.debug("Received {} messages from Kafka", records.count());

          // Process each message
          for (ConsumerRecord<String, String> record : records) {
            if (shouldStop.get()) {
              break; // Stop processing if shutdown requested
            }

            processMessage(record);
          }

          // Commit offsets after successful processing
          consumer.commitSync();

        } catch (Exception e) {
          logger.error("Error in consumer loop: {}", e.getMessage());
          // Continue processing other messages
        }
      }

    } catch (Exception e) {
      logger.error("Fatal error in consumer loop: {}", e.getMessage());
    } finally {
      logger.info("Kafka consumer loop ended");
    }
  }

  /**
   * Process a single Kafka message.
   *
   * @param record The consumer record containing the message
   */
  private void processMessage(ConsumerRecord<String, String> record) {
    try {
      logger.debug(
          "Processing message - Key: {}, Partition: {}, Offset: {}",
          record.key(),
          record.partition(),
          record.offset());

      // Deserialize the message value to KafkaEventData
      KafkaEventData eventData = objectMapper.readValue(record.value(), KafkaEventData.class);

      // Validate business event type
      if (!ADK_EVENT.equals(eventData.getBusinessEvent())) {
        logger.warn("Received non-adk-event event: {}, skipping", eventData.getBusinessEvent());
        return;
      }

      // Process the event data
      boolean success = eventRepository.processEventData(eventData);

      if (success) {
        logger.debug(
            "Successfully processed message - Key: {}, Session: {}",
            record.key(),
            eventData.getEventAttributes().getId());
      } else {
        logger.error(
            "Failed to process message - Key: {}, Session: {}",
            record.key(),
            eventData.getEventAttributes().getId());
      }

    } catch (Exception e) {
      logger.error(
          "Error processing message - Key: {}, Value: {}, Error: {}",
          record.key(),
          record.value(),
          e.getMessage());
      // Continue to next message (no retry as per requirements)
    }
  }

  /**
   * Get the current consumer configuration.
   *
   * @return Configuration info as string
   */
  public String getConfiguration() {
    return String.format(
        "KafkaConsumerService{broker='%s', topic='%s', group='%s', running=%s}",
        kafkaBrokerAddress, topicName, consumerGroup, running.get());
  }
}
