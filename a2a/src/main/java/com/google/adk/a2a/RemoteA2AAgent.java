package com.google.adk.a2a;

import static com.google.common.base.Strings.nullToEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.adk.a2a.common.A2AClientError;
import com.google.adk.a2a.common.A2AMetadata;
import com.google.adk.a2a.converters.EventConverter;
import com.google.adk.a2a.converters.ResponseConverter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.Part;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent that communicates with a remote A2A agent via A2A client.
 *
 * <p>This agent supports multiple ways to specify the remote agent:
 *
 * <ol>
 *   <li>Direct AgentCard object
 *   <li>URL to agent card JSON
 *   <li>File path to agent card JSON
 * </ol>
 *
 * <p>The agent handles:
 *
 * <ul>
 *   <li>Agent card resolution and validation
 *   <li>A2A message conversion and error handling
 *   <li>Session state management across requests
 * </ul>
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public class RemoteA2AAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(RemoteA2AAgent.class);
  private static final ObjectMapper objectMapper =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final AgentCard agentCard;
  private final Client a2aClient;
  private String description;
  private final boolean streaming;

  // Internal constructor used by builder
  private RemoteA2AAgent(Builder builder) {
    super(
        builder.name,
        builder.description,
        builder.subAgents,
        builder.beforeAgentCallback,
        builder.afterAgentCallback);

    if (builder.a2aClient == null) {
      throw new IllegalArgumentException("a2aClient cannot be null");
    }

    this.a2aClient = builder.a2aClient;
    if (builder.agentCard != null) {
      this.agentCard = builder.agentCard;
    } else {
      try {
        this.agentCard = this.a2aClient.getAgentCard();
      } catch (A2AClientException e) {
        throw new AgentCardResolutionError("Failed to resolve agent card", e);
      }
    }
    if (this.agentCard == null) {
      throw new IllegalArgumentException("agentCard cannot be null");
    }
    this.description = nullToEmpty(builder.description);
    // If builder description is empty, use the one from AgentCard
    if (this.description.isEmpty() && this.agentCard.description() != null) {
      this.description = this.agentCard.description();
    }
    this.streaming = this.agentCard.capabilities().streaming();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link RemoteA2AAgent}. */
  public static class Builder {
    private String name;
    private AgentCard agentCard;
    private Client a2aClient;
    private String description = "";
    private List<? extends BaseAgent> subAgents;
    private List<Callbacks.BeforeAgentCallback> beforeAgentCallback;
    private List<Callbacks.AfterAgentCallback> afterAgentCallback;

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder agentCard(AgentCard agentCard) {
      this.agentCard = agentCard;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder subAgents(List<? extends BaseAgent> subAgents) {
      this.subAgents = subAgents;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder beforeAgentCallback(List<Callbacks.BeforeAgentCallback> beforeAgentCallback) {
      this.beforeAgentCallback = beforeAgentCallback;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder afterAgentCallback(List<Callbacks.AfterAgentCallback> afterAgentCallback) {
      this.afterAgentCallback = afterAgentCallback;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder a2aClient(Client a2aClient) {
      this.a2aClient = a2aClient;
      return this;
    }

    public RemoteA2AAgent build() {
      return new RemoteA2AAgent(this);
    }
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    // Construct A2A Message from the last ADK event
    List<Event> sessionEvents = invocationContext.session().events();

    if (sessionEvents.isEmpty()) {
      logger.warn("No events in session, cannot send message to remote agent.");
      return Flowable.empty();
    }

    Optional<Message> a2aMessageOpt = EventConverter.convertEventsToA2AMessage(invocationContext);

    if (a2aMessageOpt.isEmpty()) {
      logger.warn("Failed to convert event to A2A message.");
      return Flowable.empty();
    }

    Message originalMessage = a2aMessageOpt.get();
    String requestJson = serializeMessageToJson(originalMessage);

    return Flowable.create(
        emitter -> {
          StreamHandler handler =
              new StreamHandler(
                  emitter.serialize(), invocationContext, requestJson, streaming, name());
          ImmutableList<BiConsumer<ClientEvent, AgentCard>> consumers =
              ImmutableList.of(handler::handleEvent);
          a2aClient.sendMessage(originalMessage, consumers, handler::handleError, null);
        },
        BackpressureStrategy.BUFFER);
  }

  private @Nullable String serializeMessageToJson(Message message) {
    try {
      return objectMapper.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      logger.warn("Failed to serialize request", e);
      return null;
    }
  }

  private static class StreamHandler {
    private final FlowableEmitter<Event> emitter;
    private final InvocationContext invocationContext;
    private final String requestJson;
    private final boolean streaming;
    private final String agentName;
    private boolean done = false;
    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder thoughtsBuffer = new StringBuilder();

    StreamHandler(
        FlowableEmitter<Event> emitter,
        InvocationContext invocationContext,
        String requestJson,
        boolean streaming,
        String agentName) {
      this.emitter = emitter;
      this.invocationContext = invocationContext;
      this.requestJson = requestJson;
      this.streaming = streaming;
      this.agentName = agentName;
    }

    synchronized void handleError(Throwable e) {
      // Mark the flow as done if it is already cancelled.
      if (!done) {
        done = emitter.isCancelled();
      }

      // If the flow is already done, stop processing.
      if (done) {
        return;
      }
      // If the error is raised, complete the flow with an error.
      done = true;
      emitter.tryOnError(new A2AClientError("Failed to communicate with the remote agent", e));
    }

    // TODO: b/483038527 - The synchronized block might block the thread, we should optimize for
    // performance in the future.
    synchronized void handleEvent(ClientEvent clientEvent, AgentCard unused) {
      // Mark the flow as done if it is already cancelled.
      if (!done) {
        done = emitter.isCancelled();
      }

      // If the flow is already done, stop processing.
      if (done) {
        return;
      }

      Optional<Event> eventOpt =
          ResponseConverter.clientEventToEvent(clientEvent, invocationContext);
      eventOpt.ifPresent(
          event -> {
            addMetadata(event, clientEvent);

            if (isCompleted(clientEvent)) {
              // Terminal event, check if we can merge.
              boolean unusedMergeResult = mergeAggregatedContentIntoEvent(event);
              if (!unusedMergeResult) {
                emitAggregatedEventAndClearBuffer(null);
              }
            } else {
              boolean isPartial = event.partial().orElse(false);
              if (isPartial) {
                if (shouldResetBuffer(clientEvent)) {
                  clearBuffer();
                }
                boolean addedToBuffer = bufferContent(event, clientEvent);
                if (!addedToBuffer) {
                  // Partial event with no content to buffer (e.g. tool call).
                  // Flush buffer before emitting this event.
                  emitAggregatedEventAndClearBuffer(null);
                }
              } else {
                // Intermediate non-partial.
                emitAggregatedEventAndClearBuffer(null);
              }
            }
            emitter.onNext(event);
          });

      // For non-streaming communication, complete the flow; for streaming, wait until the client
      // marks the completion.
      if (isCompleted(clientEvent) || !streaming) {
        // Only complete the flow once.
        if (!done) {
          emitAggregatedEventAndClearBuffer(clientEvent);
          done = true;
          emitter.onComplete();
        }
      }
    }

    private void addMetadata(Event event, ClientEvent clientEvent) {
      ImmutableList.Builder<CustomMetadata> eventMetadataBuilder = ImmutableList.builder();
      event.customMetadata().ifPresent(eventMetadataBuilder::addAll);
      if (requestJson != null) {
        eventMetadataBuilder.add(
            CustomMetadata.builder()
                .key(A2AMetadata.Key.REQUEST.getValue())
                .stringValue(requestJson)
                .build());
      }
      try {
        if (clientEvent != null) {
          eventMetadataBuilder.add(
              CustomMetadata.builder()
                  .key(A2AMetadata.Key.RESPONSE.getValue())
                  .stringValue(objectMapper.writeValueAsString(clientEvent))
                  .build());
        }
      } catch (JsonProcessingException e) {
        // metadata serialization is not critical for agent execution, so we just log and continue.
        logger.warn("Failed to serialize response metadata", e);
      }
      event.setCustomMetadata(eventMetadataBuilder.build());
    }

    /**
     * Buffers the content from the event into the text and thoughts buffers.
     *
     * @return true if the event has content that was added to the buffer, false otherwise.
     */
    private boolean bufferContent(Event event, ClientEvent clientEvent) {
      if (!shouldBuffer(clientEvent)) {
        return false;
      }

      boolean updated = false;
      for (Part part : eventParts(event)) {
        if (part.text().isPresent()) {
          String t = part.text().get();
          if (part.thought().orElse(false)) {
            thoughtsBuffer.append(t);
            updated = true;
          } else {
            textBuffer.append(t);
            updated = true;
          }
        }
      }
      return updated;
    }

    private boolean shouldBuffer(ClientEvent event) {
      if (event instanceof TaskUpdateEvent taskUpdateEvent) {
        Object innerEvent = taskUpdateEvent.getUpdateEvent();
        if (innerEvent instanceof TaskArtifactUpdateEvent) {
          return true;
        }
        if (innerEvent instanceof TaskStatusUpdateEvent) {
          return false;
        }
        return true;
      }
      if (event instanceof MessageEvent) {
        return true;
      }
      if (event instanceof TaskEvent taskEvent) {
        return !taskEvent.getTask().getArtifacts().isEmpty();
      }
      return true;
    }

    private boolean shouldResetBuffer(ClientEvent event) {
      if (event instanceof TaskUpdateEvent taskUpdateEvent) {
        Object innerEvent = taskUpdateEvent.getUpdateEvent();
        if (innerEvent instanceof TaskArtifactUpdateEvent artifactEvent) {
          return Objects.equals(artifactEvent.isAppend(), false)
              && Objects.equals(artifactEvent.isLastChunk(), false);
        }
      }
      return false;
    }

    private void clearBuffer() {
      thoughtsBuffer.setLength(0);
      textBuffer.setLength(0);
    }

    private void emitAggregatedEventAndClearBuffer(@Nullable ClientEvent triggerEvent) {
      if (thoughtsBuffer.length() > 0 || textBuffer.length() > 0) {
        List<Part> parts = new ArrayList<>();
        if (thoughtsBuffer.length() > 0) {
          parts.add(Part.builder().thought(true).text(thoughtsBuffer.toString()).build());
        }
        if (textBuffer.length() > 0) {
          parts.add(Part.builder().text(textBuffer.toString()).build());
        }
        Content aggregatedContent = Content.builder().role("model").parts(parts).build();
        emitter.onNext(createAggregatedEvent(aggregatedContent, triggerEvent));
        clearBuffer();
      }
    }

    private boolean mergeAggregatedContentIntoEvent(Event event) {
      if (thoughtsBuffer.isEmpty() && textBuffer.isEmpty()) {
        return false;
      }
      boolean hasContent =
          event.content().isPresent()
              && !event.content().get().parts().orElse(ImmutableList.of()).isEmpty();
      if (hasContent) {
        return false;
      }

      List<Part> parts = new ArrayList<>();
      if (thoughtsBuffer.length() > 0) {
        parts.add(Part.builder().thought(true).text(thoughtsBuffer.toString()).build());
      }
      if (textBuffer.length() > 0) {
        parts.add(Part.builder().text(textBuffer.toString()).build());
      }
      Content aggregatedContent = Content.builder().role("model").parts(parts).build();

      event.setContent(Optional.of(aggregatedContent));

      ImmutableList.Builder<CustomMetadata> newMetadata = ImmutableList.builder();
      event.customMetadata().ifPresent(newMetadata::addAll);
      newMetadata.add(
          CustomMetadata.builder()
              .key(A2AMetadata.Key.AGGREGATED.getValue())
              .stringValue("true")
              .build());
      event.setCustomMetadata(newMetadata.build());

      clearBuffer();
      return true;
    }

    private Event createAggregatedEvent(Content content, @Nullable ClientEvent triggerEvent) {
      ImmutableList.Builder<CustomMetadata> aggMetadataBuilder = ImmutableList.builder();
      aggMetadataBuilder.add(
          CustomMetadata.builder()
              .key(A2AMetadata.Key.AGGREGATED.getValue())
              .stringValue("true")
              .build());
      if (requestJson != null) {
        aggMetadataBuilder.add(
            CustomMetadata.builder()
                .key(A2AMetadata.Key.REQUEST.getValue())
                .stringValue(requestJson)
                .build());
      }
      if (triggerEvent != null) {
        try {
          aggMetadataBuilder.add(
              CustomMetadata.builder()
                  .key(A2AMetadata.Key.RESPONSE.getValue())
                  .stringValue(objectMapper.writeValueAsString(triggerEvent))
                  .build());
        } catch (JsonProcessingException e) {
          logger.warn("Failed to serialize response metadata for aggregated event", e);
        }
      }

      return Event.builder()
          .id(UUID.randomUUID().toString())
          .invocationId(invocationContext.invocationId())
          .author(agentName)
          .content(content)
          .timestamp(Instant.now().toEpochMilli())
          .customMetadata(aggMetadataBuilder.build())
          .build();
    }
  }

  private static boolean isCompleted(ClientEvent event) {
    TaskState executionState = TaskState.UNKNOWN;
    if (event instanceof TaskEvent taskEvent) {
      executionState = taskEvent.getTask().getStatus().state();
    } else if (event instanceof TaskUpdateEvent updateEvent) {
      executionState = updateEvent.getTask().getStatus().state();
    }
    return executionState.equals(TaskState.COMPLETED);
  }

  private static ImmutableList<Part> eventParts(Event event) {
    return ImmutableList.copyOf(event.content().flatMap(Content::parts).orElse(ImmutableList.of()));
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    throw new UnsupportedOperationException(
        "runLiveImpl for " + getClass() + " via A2A is not implemented.");
  }

  /** Exception thrown when the agent card cannot be resolved. */
  public static class AgentCardResolutionError extends RuntimeException {
    public AgentCardResolutionError(String message) {
      super(message);
    }

    public AgentCardResolutionError(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Exception thrown when a type error occurs. */
  public static class TypeError extends RuntimeException {
    public TypeError(String message) {
      super(message);
    }
  }
}
