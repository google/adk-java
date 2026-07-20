/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import java.io.IOException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class A2aServerTest {

  @Mock private Server mockServer;
  @Mock private HttpClient mockHttpClient;
  @Mock private HttpResponse<String> mockHttpResponse;

  private A2aServer a2aServer;

  @BeforeEach
  void setUp() throws IOException {
    when(mockServer.getPort()).thenReturn(8080);
  }

  @Test
  void testStartAndStop_withRegistry() throws IOException, InterruptedException {
    URL registryUrl = new URL("http://localhost:8080");
    a2aServer = new A2aServer(mockServer, registryUrl, mockHttpClient, 8080);

    when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(CompletableFuture.completedFuture(mockHttpResponse));

    when(mockServer.shutdown()).thenReturn(mockServer);
    when(mockServer.awaitTermination(any(long.class), any(java.util.concurrent.TimeUnit.class)))
        .thenReturn(true);

    a2aServer.start(false);
    verify(mockServer).start();
    verify(mockHttpClient).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

    a2aServer.stop();
    verify(mockServer).shutdown();
  }

  @Test
  void testStartAndStop_withoutRegistry() throws IOException, InterruptedException {
    a2aServer = new A2aServer(mockServer, null, mockHttpClient, 8080);
    when(mockServer.shutdown()).thenReturn(mockServer);
    when(mockServer.awaitTermination(any(long.class), any(java.util.concurrent.TimeUnit.class)))
        .thenReturn(true);

    a2aServer.start(false);
    verify(mockServer).start();

    a2aServer.stop();
    verify(mockServer).shutdown();
  }
}
