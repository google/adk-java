# tokt - ADK Java on the ADK Kotlin engine

`tokt` ("to Kotlin") is a one-way interop that lets you run **existing ADK
Java** components - tools, toolsets, plugins, models, and services - on the
**ADK Kotlin** engine, without rewriting them.

-   `JavaAdkToKt` adapts individual ADK Java components into their ADK Kotlin
    equivalents. Assemble the adapted pieces into a native Kotlin `LlmAgent` /
    `App`.
-   `KotlinAdkToJava.asJavaRunner` wraps the resulting Kotlin-engine `Runner`
    back in the ADK Java `Runner` API, so existing Java call sites stay
    unchanged.

## Run existing ADK Java components on the Kotlin engine

```java
import com.google.adk.kt.agents.LlmAgent; // ADK Kotlin-engine agent
import com.google.adk.kt.apps.App;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.runner.Runner; // ADK Java Runner API
import com.google.adk.tokt.JavaAdkToKt;
import com.google.adk.tokt.KotlinAdkToJava;

// Your existing ADK Java components:
BaseLlm model = new Gemini("gemini-flash-latest", client);
BaseTool weatherTool = FunctionTool.create(WeatherTools.class, "getWeather");
BaseToolset mathToolset = new MathToolset();
BasePlugin loggingPlugin = new LoggingPlugin();

// Adapt them and assemble a native Kotlin-engine agent + app:
LlmAgent agent =
    LlmAgent.builder()
        .name("assistant")
        .model(JavaAdkToKt.asKtModel(model))
        .tools(JavaAdkToKt.asKtTools(List.of(weatherTool)))
        .toolsets(JavaAdkToKt.asKtToolsets(List.of(mathToolset)))
        .build();
App app =
    App.builder()
        .appName("assistant")
        .rootAgent(agent)
        .plugins(JavaAdkToKt.asKtPlugins(List.of(loggingPlugin)))
        .build();

// Drive the Kotlin-engine runner through the familiar ADK Java Runner API:
Runner runner = KotlinAdkToJava.asJavaRunner(InMemoryRunner.builder().app(app).build());
List<Event> events =
    runner
        .runAsync(
            "user", "session", message, RunConfig.builder().autoCreateSession(true).build())
        .toList()
        .blockingGet();
```

## Run a live conversation

Live runs are experimental: they build on the ADK Kotlin live API, which may
change without notice.

`runLive` runs a live (bidirectional) conversation on the Kotlin engine, fed
from an ADK Java `LiveRequestQueue`. The agent's model must support live
connections, as the Kotlin engine's `Gemini` does:

```java
import com.google.adk.agents.LiveRequestQueue; // ADK Java queue

LlmAgent liveAgent =
    LlmAgent.builder()
        .name("voice_assistant")
        .model(new com.google.adk.kt.models.Gemini("gemini-3.8-live"))
        .build();
Runner liveRunner =
    KotlinAdkToJava.asJavaRunner(
        InMemoryRunner.builder()
            .app(App.builder().appName("voice").rootAgent(liveAgent).build())
            .build());

LiveRequestQueue queue = new LiveRequestQueue();
queue.content(Content.fromParts(Part.fromText("Hello!")));
liveRunner
    .runLive("user", "session", queue, RunConfig.builder().autoCreateSession(true).build())
    .blockingSubscribe(
        event -> {
          event.outputTranscription().flatMap(Transcription::text).ifPresent(System.out::println);
          if (event.turnComplete().orElse(false)) {
            queue.close(); // Ends the run and closes the connection.
          }
        });
```

-   Events arrive on the runner's dispatcher (`Dispatchers.IO` unless you pass
    one to `asJavaRunner`), never on the thread that sends to the queue.
-   A blob with an `audio/*` MIME type goes to the model as audio, and one with
    `image/*` as a video frame. Any other blob, or one with no MIME type, is
    dropped with a warning, because the Kotlin engine has no untyped media
    input.
-   An ADK Java `FunctionTool` that returns a `Flowable` streams its items to
    the model in a live run; its `inputStream` parameter, if any, receives
    `null`.
-   `RunConfig` settings the Kotlin engine cannot honor fail the stream rather
    than being dropped.
-   `runLive(session, ...)` runs the copy of that session the Kotlin runner's
    session service stores under the runner's app, and does not update the
    passed `Session` object. A session of another app, or one that service
    lacks, fails the stream even with `autoCreateSession`.

See the `JavaAdkToKt` KDoc for the full set of adapters (including session,
artifact, and memory services) and the documented interop limits.
