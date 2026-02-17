package com.google.adk.a2a;

import static com.google.common.base.Strings.nullToEmpty;

import com.google.adk.a2a.common.A2AClientError;
import com.google.adk.a2a.converters.EventConverter;
import com.google.adk.a2a.converters.ResponseConverter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
import io.a2a.spec.TaskState;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
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

    return Flowable.create(
        emitter -> {
          FlowableEmitter<Event> flowableEmitter = emitter.serialize();
          AtomicBoolean done = new AtomicBoolean(false);
          ImmutableList<BiConsumer<ClientEvent, AgentCard>> consumers =
              ImmutableList.of(
                  (event, unused) ->
                      handleClientEvent(event, flowableEmitter, invocationContext, done));
          a2aClient.sendMessage(
              originalMessage, consumers, e -> handleClientError(e, flowableEmitter, done), null);
        },
        BackpressureStrategy.BUFFER);
  }

  private void handleClientError(Throwable e, FlowableEmitter<Event> emitter, AtomicBoolean done) {
    // Mark the flow as done if it is already cancelled.
    done.compareAndSet(false, emitter.isCancelled());

    // If the flow is already done, stop processing and exit the consumer.
    if (done.get()) {
      return;
    }
    // If the error is raised, complete the flow with an error.
    if (!done.getAndSet(true)) {
      emitter.tryOnError(new A2AClientError("Failed to communicate with the remote agent", e));
    }
  }

  private void handleClientEvent(
      ClientEvent clientEvent,
      FlowableEmitter<Event> emitter,
      InvocationContext invocationContext,
      AtomicBoolean done) {
    // Mark the flow as done if it is already cancelled.
    done.compareAndSet(false, emitter.isCancelled());

    // If the flow is already done, stop processing and exit the consumer.
    if (done.get()) {
      return;
    }

    Optional<Event> event = ResponseConverter.clientEventToEvent(clientEvent, invocationContext);
    if (event.isPresent()) {
      emitter.onNext(event.get());
    }

    // For non-streaming communication, complete the flow; for streaming, wait until the client
    // marks the completion.
    if (isCompleted(clientEvent) || !streaming) {
      // Only complete the flow once.
      if (!done.getAndSet(true)) {
        emitter.onComplete();
      }
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
