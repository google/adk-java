/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import com.google.adk.a2a.converters.RequestConverter;
import com.google.adk.a2a.converters.ResponseConverter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor that runs an ADK Agent against an A2A request and publishes updates to an event stream.
 *
 * <p>This class is similar to Python's A2aAgentExecutor and handles:
 *
 * <ul>
 *   <li>Full A2A task lifecycle (submitted → working → completed/failed)
 *   <li>Task status updates
 *   <li>Task artifact updates
 *   <li>Event conversion from ADK to A2A format
 * </ul>
 */
public class A2aAgentExecutor {
  private static final Logger logger = LoggerFactory.getLogger(A2aAgentExecutor.class);

  private final Runner runner;
  private final String appName;

  /**
   * Creates an executor with a pre-configured Runner.
   *
   * @param runner The Runner instance to use for agent execution.
   */
  public A2aAgentExecutor(Runner runner) {
    this.runner = runner;
    this.appName = runner.appName();
  }

  /**
   * Creates an executor with a BaseAgent, automatically creating a Runner with in-memory services.
   *
   * @param agent The BaseAgent to execute.
   * @param appName The application name.
   */
  public A2aAgentExecutor(BaseAgent agent, String appName) {
    this.runner =
        new Runner.Builder()
            .agent(agent)
            .appName(appName)
            .artifactService(new InMemoryArtifactService())
            .sessionService(new InMemorySessionService())
            .memoryService(new InMemoryMemoryService())
            .build();
    this.appName = appName;
  }

  /**
   * Executes an A2A request and returns a stream of A2A events (TaskStatusUpdateEvent,
   * TaskArtifactUpdateEvent, etc.).
   *
   * @param request The A2A message request.
   * @param taskId The task ID for this execution.
   * @param contextId The context ID for this execution.
   * @return A Flowable stream of A2A events.
   */
  public Flowable<io.a2a.spec.Event> execute(Message request, String taskId, String contextId) {
    if (request == null) {
      throw new IllegalArgumentException("A2A request must have a message");
    }

    // 1. Convert A2A request to ADK content
    List<Event> inputEvents =
        RequestConverter.convertAggregatedA2aMessageToAdkEvents(
            request, UUID.randomUUID().toString());

    // 2. Extract user content from events
    Content userContent = null;
    for (Event event : inputEvents) {
      if (event.content().isPresent()) {
        Content content = event.content().get();
        if (content.role().isPresent() && "user".equals(content.role().get())) {
          userContent = content;
          break;
        }
      }
    }

    if (userContent == null || userContent.parts().isEmpty()) {
      throw new IllegalArgumentException("A2A request must have user content");
    }

    // Make userContent final for lambda
    final Content finalUserContent = userContent;

    // 3. Get or create session
    final String userId = contextId; // Use contextId as userId for simplicity
    final String sessionId = contextId;

    return Flowable.fromCallable(
            () -> {
              io.reactivex.rxjava3.core.Maybe<Session> sessionMaybe =
                  runner
                      .sessionService()
                      .getSession(appName, userId, sessionId, java.util.Optional.empty());

              Session session = sessionMaybe.blockingGet();

              if (session == null) {
                java.util.concurrent.ConcurrentHashMap<String, Object> initialState =
                    new java.util.concurrent.ConcurrentHashMap<>();
                session =
                    runner
                        .sessionService()
                        .createSession(appName, userId, initialState, sessionId)
                        .blockingGet();
              }
              return session;
            })
        .flatMap(
            session -> {
              // 4. Publish task submitted event
              TaskStatusUpdateEvent submittedEvent =
                  new TaskStatusUpdateEvent(
                      taskId,
                      new TaskStatus(TaskState.SUBMITTED, request, null),
                      contextId,
                      false,
                      null);

              // 5. Publish task working event
              TaskStatusUpdateEvent workingEvent =
                  new TaskStatusUpdateEvent(
                      taskId,
                      new TaskStatus(TaskState.WORKING, null, null),
                      contextId,
                      false,
                      null);

              // 6. Execute agent
              Flowable<Event> agentEvents =
                  runner.runAsync(
                      userId,
                      sessionId,
                      finalUserContent,
                      RunConfig.builder()
                          .setStreamingMode(RunConfig.StreamingMode.SSE)
                          .setMaxLlmCalls(20)
                          .build());

              // 7. Convert ADK events to A2A task artifact events
              Flowable<io.a2a.spec.Event> a2aEvents =
                  agentEvents
                      .flatMap(
                          adkEvent -> {
                            List<io.a2a.spec.Event> converted = new ArrayList<>();

                            // Convert to A2A message
                            Message a2aMessage =
                                ResponseConverter.eventToMessage(adkEvent, contextId);
                            if (a2aMessage != null && !a2aMessage.getParts().isEmpty()) {
                              // Create artifact update event
                              Artifact artifact =
                                  new Artifact.Builder()
                                      .artifactId(UUID.randomUUID().toString())
                                      .parts(a2aMessage.getParts())
                                      .build();
                              TaskArtifactUpdateEvent artifactEvent =
                                  new TaskArtifactUpdateEvent.Builder()
                                      .taskId(taskId)
                                      .contextId(contextId)
                                      .artifact(artifact)
                                      .lastChunk(false)
                                      .build();
                              converted.add(artifactEvent);
                            }

                            return Flowable.fromIterable(converted);
                          })
                      .onErrorResumeNext(
                          error -> {
                            logger.error("Error converting ADK event to A2A event", error);
                            TaskStatusUpdateEvent errorEvent =
                                new TaskStatusUpdateEvent(
                                    taskId,
                                    new TaskStatus(
                                        TaskState.FAILED,
                                        new Message.Builder()
                                            .messageId(UUID.randomUUID().toString())
                                            .role(Message.Role.AGENT)
                                            .parts(
                                                ImmutableList.of(
                                                    new io.a2a.spec.TextPart(
                                                        "Error: " + error.getMessage())))
                                            .build(),
                                        null),
                                    contextId,
                                    true,
                                    null);
                            return Flowable.just(errorEvent);
                          });

              // 8. Create final completion events
              TaskArtifactUpdateEvent finalArtifact =
                  new TaskArtifactUpdateEvent.Builder()
                      .taskId(taskId)
                      .contextId(contextId)
                      .artifact(
                          new Artifact.Builder()
                              .artifactId(UUID.randomUUID().toString())
                              .parts(ImmutableList.of())
                              .build())
                      .lastChunk(true)
                      .build();

              TaskStatusUpdateEvent completedEvent =
                  new TaskStatusUpdateEvent(
                      taskId,
                      new TaskStatus(TaskState.COMPLETED, null, null),
                      contextId,
                      true,
                      null);

              // Combine all events in sequence: submitted → working → agent events → completion
              return Flowable.just((io.a2a.spec.Event) submittedEvent)
                  .concatWith(Flowable.just((io.a2a.spec.Event) workingEvent))
                  .concatWith(a2aEvents)
                  .concatWith(Flowable.just((io.a2a.spec.Event) finalArtifact))
                  .concatWith(Flowable.just((io.a2a.spec.Event) completedEvent));
            })
        .onErrorResumeNext(
            error -> {
              logger.error("Error executing A2A request", error);
              TaskStatusUpdateEvent errorEvent =
                  new TaskStatusUpdateEvent(
                      taskId,
                      new TaskStatus(
                          TaskState.FAILED,
                          new Message.Builder()
                              .messageId(UUID.randomUUID().toString())
                              .role(Message.Role.AGENT)
                              .parts(
                                  ImmutableList.of(
                                      new io.a2a.spec.TextPart("Error: " + error.getMessage())))
                              .build(),
                          null),
                      contextId,
                      true,
                      null);
              return Flowable.just(errorEvent);
            });
  }
}
