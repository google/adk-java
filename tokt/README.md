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

See the `JavaAdkToKt` KDoc for the full set of adapters (including session,
artifact, and memory services) and the documented interop limits.
