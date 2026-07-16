/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import com.google.adk.agents.BaseAgent;
import java.io.IOException;
import java.net.URL;

/**
 * A utility class to easily expose an ADK BaseAgent as a standalone A2A gRPC server. This class
 * provides a Python-like {@code to_a2a} experience for Java developers.
 *
 * <p>This utility abstracts away the boilerplate of creating a gRPC server, allowing developers to
 * expose their agents with minimal code.
 *
 * <p><b>Example usage:</b>
 *
 * <pre>{@code
 * import com.google.adk.agents.LlmAgent;
 * import com.google.adk.a2a.grpc.A2aGrpcServer;
 *
 * public class Main {
 *     public static void main(String[] args) {
 *         BaseAgent agent = new LlmAgent(...);
 *         // This single line starts the entire gRPC A2A server!
 *         A2aGrpcServer.run(agent, args);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Note:</b> This assumes the gRPC service definition (`.proto` file) and generated classes
 * ({@code A2AServiceGrpc}, {@code SendMessageRequest}, {@code SendMessageResponse}) are available
 * in the classpath through the Maven {@code protobuf-maven-plugin} configuration.
 */
public final class A2aGrpcServer {

  private A2aGrpcServer() {
    // Utility class - prevent instantiation
  }

  /**
   * Starts an A2A gRPC server for the given agent instance. This method creates and starts a gRPC
   * server on the default port (8080).
   *
   * <p>The server will run until the JVM is shut down. A shutdown hook is automatically registered
   * to ensure graceful shutdown.
   *
   * @param agent The ADK agent to expose as a gRPC service.
   * @throws IOException If the server fails to start.
   * @throws InterruptedException If the server is interrupted while waiting for termination.
   */
  public static void run(BaseAgent agent) throws IOException, InterruptedException {
    run(agent, 8080);
  }

  /**
   * Starts an A2A gRPC server for the given agent instance on the specified port.
   *
   * <p>The server will run until the JVM is shut down. A shutdown hook is automatically registered
   * to ensure graceful shutdown.
   *
   * @param agent The ADK agent to expose as a gRPC service.
   * @param port The port number on which the server should listen.
   * @throws IOException If the server fails to start.
   * @throws InterruptedException If the server is interrupted while waiting for termination.
   */
  public static void run(BaseAgent agent, int port) throws IOException, InterruptedException {
    run(agent, port, null);
  }

  /**
   * Starts an A2A gRPC server for the given agent instance with optional registry integration.
   *
   * <p>The server will run until the JVM is shut down. A shutdown hook is automatically registered
   * to ensure graceful shutdown.
   *
   * @param agent The ADK agent to expose as a gRPC service.
   * @param port The port number on which the server should listen.
   * @param registryUrl Optional URL of the service registry. If provided, the server will register
   *     itself with the registry upon startup and unregister upon shutdown.
   * @throws IOException If the server fails to start.
   * @throws InterruptedException If the server is interrupted while waiting for termination.
   */
  public static void run(BaseAgent agent, int port, URL registryUrl)
      throws IOException, InterruptedException {
    if (agent == null) {
      throw new IllegalArgumentException("Agent cannot be null");
    }

    A2aServer server = new A2aServerBuilder(agent).port(port).withRegistry(registryUrl).build();

    // Start the server and block until termination
    server.start();
  }

  /**
   * Creates and returns an {@link A2aServerBuilder} for advanced configuration. This allows for
   * more fine-grained control over the server setup.
   *
   * <p><b>Example:</b>
   *
   * <pre>{@code
   * A2aServer server = A2aGrpcServer.builder(agent)
   *     .port(9090)
   *     .withRegistry(new URL("http://localhost:8081"))
   *     .build();
   * server.start();
   * }</pre>
   *
   * @param agent The ADK agent to expose as a gRPC service.
   * @return A new {@link A2aServerBuilder} instance.
   */
  public static A2aServerBuilder builder(BaseAgent agent) {
    return new A2aServerBuilder(agent);
  }
}
