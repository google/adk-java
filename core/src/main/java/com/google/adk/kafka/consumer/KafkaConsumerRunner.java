package com.google.adk.kafka.consumer;

import com.google.adk.utils.PropertiesHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone application runner for the Kafka consumer service. This class provides a main method
 * to run the Kafka consumer as a standalone application with proper shutdown handling.
 *
 * <p>Usage: java -cp ... com.google.adk.kafka.consumer.KafkaConsumerRunner
 *
 * <p>The application will: 1. Initialize the Kafka consumer service 2. Start consuming messages 3.
 * Handle shutdown signals gracefully 4. Clean up resources on exit
 */
public class KafkaConsumerRunner {

  private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerRunner.class);

  private static KafkaConsumerService consumerService;
  private static volatile boolean shutdownRequested = false;

  /**
   * Main method to run the Kafka consumer application.
   *
   * @param args Command line arguments (not used)
   */
  public static void main(String[] args) {
    logger.info("Starting Kafka Consumer Runner...");

    try {
      // Initialize PropertiesHelper first
      String propertiesFilePath = "core/config.ini";
      if (args.length > 0) {
        propertiesFilePath = args[0];
      }
      String environment = "production";
      if (args.length > 1) {
        environment = args[1];
      }

      logger.info(
          "Loading properties from: {} with environment: {}", propertiesFilePath, environment);
      PropertiesHelper.loadProperties(propertiesFilePath, environment);

      // Initialize the consumer service
      consumerService = new KafkaConsumerService();
      logger.info("Consumer service initialized: {}", consumerService.getConfiguration());

      // Set up shutdown hook for graceful termination
      setupShutdownHook();

      // Start the consumer service
      consumerService.start();

      // Keep the main thread alive
      keepAlive();

    } catch (Exception e) {
      logger.error("Fatal error in Kafka Consumer Runner: {}", e.getMessage(), e);
      System.exit(1);
    } finally {
      cleanup();
    }

    logger.info("Kafka Consumer Runner stopped");
    System.exit(0);
  }

  /**
   * Set up shutdown hook to handle graceful termination. This hook will be called when the JVM
   * receives a termination signal.
   */
  private static void setupShutdownHook() {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutdown signal received, initiating graceful shutdown...");
                  shutdownRequested = true;

                  if (consumerService != null && consumerService.isRunning()) {
                    consumerService.stop();
                  }

                  logger.info("Shutdown hook completed");
                }));
  }

  /**
   * Keep the main thread alive while the consumer is running. This method will block until shutdown
   * is requested.
   */
  private static void keepAlive() {
    logger.info("Kafka consumer is running. Press Ctrl+C to stop.");

    try {
      // Keep the main thread alive
      while (!shutdownRequested && consumerService.isRunning()) {
        Thread.sleep(1000); // Sleep for 1 second
      }

      // If we exit the loop due to consumer stopping (not shutdown request)
      if (!shutdownRequested) {
        logger.warn("Consumer service stopped unexpectedly");
      }

    } catch (InterruptedException e) {
      logger.info("Main thread interrupted, initiating shutdown...");
      shutdownRequested = true;
    }
  }

  /** Clean up resources before exit. */
  private static void cleanup() {
    logger.info("Cleaning up resources...");

    if (consumerService != null && consumerService.isRunning()) {
      logger.info("Stopping consumer service...");
      consumerService.stop();
    }

    logger.info("Cleanup completed");
  }

  /**
   * Get the current status of the consumer service. This method can be used for monitoring
   * purposes.
   *
   * @return Status information as string
   */
  public static String getStatus() {
    if (consumerService == null) {
      return "Consumer service not initialized";
    }

    return String.format(
        "Consumer service status: %s, Shutdown requested: %s",
        consumerService.isRunning() ? "RUNNING" : "STOPPED", shutdownRequested);
  }
}
