package com.example.helloworld;

import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** */
public final class HelloWorldRun {
  private final String userId;
  private final String sessionId;
  private final Runner runner;

  private HelloWorldRun() {
    String appName = "hello-world-app"; // Example app name
    this.userId = "hello-world-user"; // Example user id
    this.sessionId = UUID.randomUUID().toString();

    InMemorySessionService sessionService = new InMemorySessionService();
    this.runner =
        new Runner(
            HelloWorldAgent.ROOT_AGENT,
            appName,
            new InMemoryArtifactService(),
            sessionService,
            new InMemoryMemoryService());

    ConcurrentMap<String, Object> initialState =
        new ConcurrentHashMap<>(); // No initial state needed for this example
    var unused =
        sessionService.createSession(appName, userId, initialState, sessionId).blockingGet();
  }

  private void run(String prompt) {
    System.out.println("You> " + prompt);
    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text(prompt).build()))
            .build();
    RunConfig runConfig = RunConfig.builder().build();
    Flowable<Event> eventStream =
        this.runner.runAsync(this.userId, this.sessionId, userMessage, runConfig);
    List<Event> agentEvents = Lists.newArrayList(eventStream.blockingIterable());

    StringBuilder sb = new StringBuilder();
    sb.append("Agent> ");
    for (Event event : agentEvents) {
      sb.append(event.stringifyContent().stripTrailing());
    }
    System.out.println(sb);
  }

  public static void main(String[] args) {
    HelloWorldRun runner = new HelloWorldRun();
    runner.run("Hi. Roll a die of 60 sides.");
    if (args.length > 0 && Objects.equals(args[0], "--run-extended")) {
      runner.run("Roll the die again.");
      runner.run("Roll an 256-sided die.");
      runner.run("Roll the die again.");
      runner.run(
          "Roll a die with a random number of sides that is greater than 10 and less than 1000.");
      runner.run("What numbers did I get?");
      runner.run("Check all of them for prime numbers.");
    }
  }
}
