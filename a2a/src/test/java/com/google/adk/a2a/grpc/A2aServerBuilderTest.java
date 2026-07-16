/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.BaseAgent;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.http.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class A2aServerBuilderTest {

  private BaseAgent mockAgent;

  @BeforeEach
  void setUp() {
    mockAgent = mock(BaseAgent.class);
  }

  @Test
  void testBuild_Default() {
    A2aServerBuilder builder = new A2aServerBuilder(mockAgent);
    A2aServer server = builder.build();
    assertNotNull(server);
  }

  @Test
  void testPort_Valid() {
    A2aServerBuilder builder = new A2aServerBuilder(mockAgent).port(8081);
    A2aServer server = builder.build();
    assertNotNull(server);
  }

  @Test
  void testPort_Invalid() {
    A2aServerBuilder builder = new A2aServerBuilder(mockAgent);
    assertThrows(IllegalArgumentException.class, () -> builder.port(0));
    assertThrows(IllegalArgumentException.class, () -> builder.port(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.port(65536));
  }

  @Test
  void testWithRegistry() throws MalformedURLException {
    URL registryUrl = new URL("http://localhost:8080");
    A2aServerBuilder builder = new A2aServerBuilder(mockAgent).withRegistry(registryUrl);
    A2aServer server = builder.build();
    assertNotNull(server);
  }

  @Test
  void testHttpClient() {
    HttpClient mockHttpClient = mock(HttpClient.class);
    A2aServerBuilder builder = new A2aServerBuilder(mockAgent).httpClient(mockHttpClient);
    A2aServer server = builder.build();
    assertNotNull(server);
  }

  @Test
  void testConstructor_NullAgent() {
    assertThrows(IllegalArgumentException.class, () -> new A2aServerBuilder(null));
  }
}
