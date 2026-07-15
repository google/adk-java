package com.example.a2a_basic;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

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

  private Flowable<Event> run(String prompt) {
    System.out.println("\n--------------------------------------------------");
    System.out.println("You> " + prompt);
    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text(prompt).build()))
            .build();
    return processRunRequest(userMessage);
  }

  private Flowable<Event> processRunRequest(Content inputContent) {
    RunConfig runConfig = RunConfig.builder().build();
    return this.runner.runAsync(this.userId, this.sessionId, inputContent, runConfig);
  }

  private static void printOutEvent(Event event) {
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

  public static void main(String[] args) {
    String primeAgentUrl = args.length > 0 ? args[0] : "http://localhost:8081/a2a/remote/v1";
    BaseAgent agent = A2AAgent.createRootAgent(primeAgentUrl);
    A2AAgentRun a2aRun = new A2AAgentRun(agent);

    List<Event> events =
        a2aRun.run("Roll a dice of 6 sides.").toList().timeout(90, TimeUnit.SECONDS).blockingGet();

    events.forEach(A2AAgentRun::printOutEvent);

    events =
        a2aRun.run("Is this a prime number?").toList().timeout(90, TimeUnit.SECONDS).blockingGet();

    events.forEach(A2AAgentRun::printOutEvent);
  }
}
