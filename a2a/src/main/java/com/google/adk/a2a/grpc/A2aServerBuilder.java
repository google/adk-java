/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import com.google.adk.agents.BaseAgent;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.net.URL;
import java.net.http.HttpClient;

/** A builder for creating a lightweight, standalone A2A gRPC server. */
public class A2aServerBuilder {

  private final BaseAgent agent;
  private int port = 8080; // Default port
  private URL registryUrl;
  private HttpClient httpClient;

  /**
   * Constructs a new builder for a given ADK agent.
   *
   * @param agent The ADK {@link BaseAgent} to be exposed via the gRPC service.
   */
  public A2aServerBuilder(BaseAgent agent) {
    if (agent == null) {
      throw new IllegalArgumentException("Agent cannot be null");
    }
    this.agent = agent;
  }

  /**
   * Sets the port on which the server should listen.
   *
   * @param port The port number.
   * @return This builder instance for chaining.
   */
  public A2aServerBuilder port(int port) {
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("Port must be between 1 and 65535");
    }
    this.port = port;
    return this;
  }

  /**
   * Sets the URL of the A2A service registry. If set, the server will attempt to register itself
   * with the registry upon startup.
   *
   * @param registryUrl The URL of the service registry.
   * @return This builder instance for chaining.
   */
  public A2aServerBuilder withRegistry(URL registryUrl) {
    this.registryUrl = registryUrl;
    return this;
  }

  /**
   * Sets the {@link HttpClient} to be used for registry communication. If not set, a default client
   * will be created.
   *
   * @param httpClient The HTTP client to use.
   * @return This builder instance for chaining.
   */
  public A2aServerBuilder httpClient(HttpClient httpClient) {
    this.httpClient = httpClient;
    return this;
  }

  /**
   * Builds the {@link A2aServer} instance.
   *
   * @return A new {@link A2aServer} configured with the settings from this builder.
   */
  public A2aServer build() {
    Server grpcServer = ServerBuilder.forPort(port).addService(new A2aService(agent)).build();
    HttpClient client = (httpClient != null) ? httpClient : HttpClient.newHttpClient();
    return new A2aServer(grpcServer, registryUrl, client, this.port);
  }
}
