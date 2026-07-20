/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import java.net.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class A2aGrpcServerTest {

  @Mock private BaseAgent mockAgent;

  @BeforeEach
  void setUp() {
    when(mockAgent.runAsync(any(InvocationContext.class)))
        .thenReturn(Flowable.just(Event.builder().author("test").build()));
  }

  @Test
  void testRun_withAgent_only() {
    assertDoesNotThrow(
        () -> {
          // This will block, so we'll test the builder pattern instead
          A2aServerBuilder builder = A2aGrpcServer.builder(mockAgent);
          assertNotNull(builder);
        });
  }

  @Test
  void testRun_withAgentAndPort() {
    assertDoesNotThrow(
        () -> {
          A2aServerBuilder builder = A2aGrpcServer.builder(mockAgent).port(9090);
          assertNotNull(builder);
        });
  }

  @Test
  void testRun_withAgentPortAndRegistry() throws Exception {
    assertDoesNotThrow(
        () -> {
          A2aServerBuilder builder =
              A2aGrpcServer.builder(mockAgent)
                  .port(9090)
                  .withRegistry(new URL("http://localhost:8081"));
          assertNotNull(builder);
        });
  }

  @Test
  void testBuilder_withNullAgent_throwsException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          A2aGrpcServer.builder(null);
        });
  }

  @Test
  void testBuilder_returnsBuilder() {
    A2aServerBuilder builder = A2aGrpcServer.builder(mockAgent);
    assertNotNull(builder);
  }

  @Test
  void testBuilder_portConfiguration() {
    A2aServerBuilder builder = A2aGrpcServer.builder(mockAgent).port(9090);
    A2aServer server = builder.build();
    assertNotNull(server);
  }

  @Test
  void testBuilder_registryConfiguration() throws Exception {
    A2aServerBuilder builder =
        A2aGrpcServer.builder(mockAgent).port(9090).withRegistry(new URL("http://localhost:8081"));
    A2aServer server = builder.build();
    assertNotNull(server);
  }
}
