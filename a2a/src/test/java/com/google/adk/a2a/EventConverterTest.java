package com.google.adk.a2a;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventConverterTest {

  @Test
  public void convertEventsToA2AMessage_preservesFunctionCallAndResponseParts() {
    // Arrange session events: user text, function call, function response.
    Part userTextPart = Part.builder().text("Roll a die").build();
    Event userEvent =
        Event.builder()
            .id("event-user")
            .author("user")
            .content(Content.builder().role("user").parts(ImmutableList.of(userTextPart)).build())
            .build();

    Part functionCallPart =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("roll_die")
                    .id("adk-call-1")
                    .args(Map.of("sides", 6))
                    .build())
            .build();
    Event callEvent =
        Event.builder()
            .id("event-call")
            .author("root_agent")
            .content(
                Content.builder()
                    .role("assistant")
                    .parts(ImmutableList.of(functionCallPart))
                    .build())
            .build();

    Part functionResponsePart =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .name("roll_die")
                    .id("adk-call-1")
                    .response(Map.of("result", 3))
                    .build())
            .build();
    Event responseEvent =
        Event.builder()
            .id("event-response")
            .author("roll_agent")
            .content(
                Content.builder()
                    .role("tool")
                    .parts(ImmutableList.of(functionResponsePart))
                    .build())
            .build();

    List<Event> events = new ArrayList<>(ImmutableList.of(userEvent, callEvent, responseEvent));
    Session session =
        Session.builder("session-1").appName("demo").userId("user").events(events).build();

    InvocationContext context =
        new InvocationContext(
            new InMemorySessionService(),
            new InMemoryArtifactService(),
            (BaseMemoryService) null,
            new PluginManager(),
            Optional.empty(),
            Optional.empty(),
            "invocation-1",
            new TestAgent(),
            session,
            Optional.empty(),
            RunConfig.builder().build(),
            false);

    // Act
    Optional<Message> maybeMessage = EventConverter.convertEventsToA2AMessage(context);

    // Assert
    assertThat(maybeMessage).isPresent();
    Message message = maybeMessage.get();
    assertThat(message.getParts()).hasSize(3);
    assertThat(message.getParts().get(0)).isInstanceOf(io.a2a.spec.TextPart.class);
    assertThat(message.getParts().get(1)).isInstanceOf(DataPart.class);
    assertThat(message.getParts().get(2)).isInstanceOf(DataPart.class);

    DataPart callDataPart = (DataPart) message.getParts().get(1);
    assertThat(callDataPart.getMetadata().get(PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY))
        .isEqualTo(PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL);
    assertThat(callDataPart.getData()).containsEntry("name", "roll_die");
    assertThat(callDataPart.getData()).containsEntry("id", "adk-call-1");
    assertThat(callDataPart.getData()).containsEntry("args", Map.of("sides", 6));

    DataPart responseDataPart = (DataPart) message.getParts().get(2);
    assertThat(responseDataPart.getMetadata().get(PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY))
        .isEqualTo(PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE);
    assertThat(responseDataPart.getData()).containsEntry("name", "roll_die");
    assertThat(responseDataPart.getData()).containsEntry("id", "adk-call-1");
    assertThat(responseDataPart.getData()).containsEntry("response", Map.of("result", 3));
  }

  private static final class TestAgent extends BaseAgent {
    TestAgent() {
      super("test_agent", "test", ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }
}
