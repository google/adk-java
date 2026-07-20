/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import com.google.gson.Gson;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages the lifecycle of a standalone A2A gRPC server. */
public class A2aServer {

  private static final Logger logger = LoggerFactory.getLogger(A2aServer.class);
  private static final Gson gson = new Gson();

  private final Server server;
  private final URL registryUrl;
  private final AgentInfo agentInfo;
  private final HttpClient httpClient;

  /**
   * Constructs a new server instance.
   *
   * @param server The underlying gRPC {@link Server} to manage.
   * @param registryUrl The URL of the service registry.
   * @param httpClient The HTTP client to use for registry communication.
   */
  public A2aServer(Server server, URL registryUrl, HttpClient httpClient, int port) {
    this.server = server;
    this.registryUrl = registryUrl;
    this.agentInfo = new AgentInfo(port);
    this.httpClient = httpClient;
  }

  /**
   * Starts the gRPC server and blocks the current thread until the server is terminated.
   *
   * @throws IOException If the server fails to start.
   * @throws InterruptedException If the server is interrupted while waiting for termination.
   */
  public void start() throws IOException, InterruptedException {
    start(true);
  }

  /**
   * Starts the gRPC server.
   *
   * @param awaitTermination If true, blocks the current thread until the server is terminated.
   * @throws IOException If the server fails to start.
   * @throws InterruptedException If the server is interrupted while waiting for termination.
   */
  public void start(boolean awaitTermination) throws IOException, InterruptedException {
    server.start();
    logger.info("A2A gRPC server started, listening on port " + server.getPort());

    if (registryUrl != null) {
      register();
    }

    // Add a shutdown hook to ensure a graceful server shutdown
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down gRPC server since JVM is shutting down");
                  try {
                    A2aServer.this.stop();
                  } catch (InterruptedException e) {
                    logger.error("gRPC server shutdown interrupted", e);
                    Thread.currentThread().interrupt(); // Preserve the interrupted status
                  }
                  logger.info("Server shut down");
                }));

    // Block until the server is terminated
    if (awaitTermination) {
      server.awaitTermination();
    }
  }

  /**
   * Gets the port on which the server is listening.
   *
   * @return The port number.
   */
  public int getPort() {
    return server.getPort();
  }

  /**
   * Stops the gRPC server gracefully.
   *
   * @throws InterruptedException If the server is interrupted while shutting down.
   */
  public void stop() throws InterruptedException {
    if (registryUrl != null) {
      unregister();
    }
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private void register() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(registryUrl + "/register"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(agentInfo)))
              .build();

      httpClient
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .whenComplete(
              (response, throwable) -> {
                if (throwable != null) {
                  logger.error("Failed to register with registry service", throwable);
                  return;
                }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                  logger.info(
                      "Successfully registered with registry service. Status: {}, Body: {}",
                      response.statusCode(),
                      response.body());
                } else {
                  logger.warn(
                      "Failed to register with registry service. Status: {}, Body: {}",
                      response.statusCode(),
                      response.body());
                }
              });
    } catch (Exception e) {
      logger.error("Error building registry request", e);
    }
  }

  private void unregister() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(registryUrl + "/unregister"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(agentInfo)))
              .build();

      httpClient
          .sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .whenComplete(
              (response, throwable) -> {
                if (throwable != null) {
                  logger.error("Failed to unregister from registry service", throwable);
                  return;
                }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                  logger.info(
                      "Successfully unregistered from registry service. Status: {}, Body: {}",
                      response.statusCode(),
                      response.body());
                } else {
                  logger.warn(
                      "Failed to unregister from registry service. Status: {}, Body: {}",
                      response.statusCode(),
                      response.body());
                }
              });
    } catch (Exception e) {
      logger.error("Error building unregister request", e);
    }
  }

  private static class AgentInfo {
    private final String name;
    private final String url;

    AgentInfo(int port) {
      this.name = "agent-" + port;
      this.url = "http://localhost:" + port;
    }

    public String getName() {
      return name;
    }

    public String getUrl() {
      return url;
    }
  }
}
