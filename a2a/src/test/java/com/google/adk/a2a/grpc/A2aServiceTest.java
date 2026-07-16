/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.grpc.stub.StreamObserver;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class A2aServiceTest {

  @Mock private BaseAgent mockAgent;
  @Mock private StreamObserver<SendMessageResponse> mockResponseObserver;

  private A2aService a2aService;

  @BeforeEach
  void setUp() {
    a2aService = new A2aService(mockAgent);
  }

  @Test
  void testSendMessage_returnsAgentResponse() {
    when(mockAgent.runAsync(any(InvocationContext.class)))
        .thenReturn(
            Flowable.just(
                Event.builder()
                    .author("test-agent")
                    .content(Content.fromParts(Part.fromText("Hello, world!")))
                    .build()));

    SendMessageRequest request =
        SendMessageRequest.newBuilder().setSessionId("test-session").setUserQuery("Hi").build();

    a2aService.sendMessage(request, mockResponseObserver);

    verify(mockResponseObserver).onNext(any(SendMessageResponse.class));
    verify(mockResponseObserver).onCompleted();
  }
}
