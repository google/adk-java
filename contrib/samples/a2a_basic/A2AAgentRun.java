package com.example.a2a_basic;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Main class to demonstrate running the A2A agent with sequential inputs. */
public final class A2AAgentRun {
  private final String userId;
  private final String sessionId;
  private final Runner runner;

  public A2AAgentRun(BaseAgent agent) {
    this.userId = "test_user";
    String appName = "A2AAgentApp";
    this.sessionId = UUID.randomUUID().toString();

    InMemoryArtifactService artifactService = new InMemoryArtifactService();
    InMemorySessionService sessionService = new InMemorySessionService();
    this.runner =
        new Runner(agent, appName, artifactService, sessionService, /* memoryService= */ null);

    ConcurrentMap<String, Object> initialState = new ConcurrentHashMap<>();
    var unused =
        sessionService.createSession(appName, userId, initialState, sessionId).blockingGet();
  }

  private List<Event> run(String prompt) {
    System.out.println("\n--------------------------------------------------");
    System.out.println("You> " + prompt);
    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text(prompt).build()))
            .build();
    return processRunRequest(userMessage);
  }

  private List<Event> processRunRequest(Content inputContent) {
    RunConfig runConfig = RunConfig.builder().build();
    Flowable<Event> eventStream =
        this.runner.runAsync(this.userId, this.sessionId, inputContent, runConfig);
    List<Event> agentEvents = Lists.newArrayList(eventStream.blockingIterable());
    System.out.println("Agent>");
    for (Event event : agentEvents) {
      if (event.content().isPresent() && event.content().get().parts().isPresent()) {
        event
            .content()
            .get()
            .parts()
            .get()
            .forEach(
                part -> {
                  if (part.text().isPresent()) {
                    System.out.println("    Text: " + part.text().get().stripTrailing());
                  }
                });
      }
      if (event.actions() != null && event.actions().transferToAgent().isPresent()) {
        System.out.println("    Actions: transferTo=" + event.actions().transferToAgent().get());
      }
      System.out.println("    Raw Event: " + event);
    }
    return agentEvents;
  }

  public static void main(String[] args) {
    String primeAgentUrl = args.length > 0 ? args[0] : "http://localhost:9876/a2a/prime_agent";
    LlmAgent agent = A2AAgent.createRootAgent(primeAgentUrl);
    A2AAgentRun a2aRun = new A2AAgentRun(agent);

    // First user input
    System.out.println("Running turn 1");
    a2aRun.run("Roll a dice of 6 sides.");

    // Follow-up input triggers the remote prime agent so the A2A request is logged.
    System.out.println("Running turn 2");
    a2aRun.run("Is this number a prime number?");
  }
}
