## Firestore Session Service for ADK

This sub-module contains an implementation of a session service for the ADK (Agent Development Kit) that uses Google Cloud Firestore as the backend for storing session data and conversation memory. This allows ADK Applications to manage user sessions in a scalable, durable manner across distributed servers and restarts.

## Getting Started

To integrate Firestore session service into your ADK project, add the following dependencies to your project's build configuration: `pom.xml` for Maven or `build.gradle` for Gradle.

### Maven Setup

<!-- x-release-please-released-start-version -->

```xml
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk</artifactId>
  <version>1.15.0</version>
</dependency>
<!-- Firestore Session Service -->
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk-firestore-session-service</artifactId>
  <version>1.15.0</version>
</dependency>
```

<!-- x-release-please-released-end -->

### Gradle Setup

<!-- x-release-please-released-start-version -->

```gradle
dependencies {
    // ADK Core
    implementation 'com.google.adk:google-adk:1.15.0'
    // Firestore Session Service
    implementation 'com.google.adk:google-adk-firestore-session-service:1.15.0'
}
```

<!-- x-release-please-released-end -->

---

## Example 1: Conversational Agent (Explicit In-Memory Artifacts for local testing)

For conversational agents that do not use persistent file artifacts, you can explicitly configure `InMemoryArtifactService`:

```java
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.FirestoreDatabaseRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Scanner;

public class ChatAgentApplication {

  public static void main(String[] args) {
    String appName = "hello-time-agent";
    BaseAgent timeAgent = initAgent();

    // 1. Initialize Firestore client
    Firestore firestore = FirestoreOptions.getDefaultInstance().getService();

    // 2. Build the Runner backed by Firestore, explicitly opting into in-memory artifacts
    Runner runner =
        FirestoreDatabaseRunner.newBuilder()
            .agent(timeAgent)
            .appName(appName)
            .firestore(firestore)
            .artifactService(new InMemoryArtifactService())
            .build();

    // 3. Create or resume a session
    Session session =
        runner.sessionService().createSession(appName, "user1234").blockingGet();

    RunConfig runConfig = RunConfig.builder().build();

    // 4. Run interactive chat loop
    try (Scanner scanner = new Scanner(System.in, UTF_8)) {
      while (true) {
        System.out.print("\nYou > ");
        String userInput = scanner.nextLine();
        if ("quit".equalsIgnoreCase(userInput)) {
          break;
        }

        Content userMsg = Content.fromParts(Part.fromText(userInput));
        Flowable<Event> events =
            runner.runAsync(session.userId(), session.id(), userMsg, runConfig);

        System.out.print("\nAgent > ");
        events.blockingForEach(
            event -> {
              if (event.finalResponse()) {
                System.out.println(event.stringifyContent());
              }
            });
      }
    }
  }

  @Schema(description = "Get the current time for a given city")
  public static Map<String, String> getCurrentTime(
      @Schema(name = "city", description = "Name of the city") String city) {
    return Map.of("city", city, "forecast", "The time is 10:30 AM.");
  }

  private static BaseAgent initAgent() {
    return LlmAgent.builder()
        .name("hello-time-agent")
        .description("Tells the current time in a specified city")
        .instruction("You are a helpful assistant that tells the current time using the 'getCurrentTime' tool.")
        .model("gemini-2.5-flash")
        .tools(FunctionTool.create(ChatAgentApplication.class, "getCurrentTime"))
        .build();
  }
}
```

---

## Example 2: Agent with Google Cloud Storage (GCS) Artifacts

When an agent produces or consumes file artifacts (such as images, generated reports, or file downloads), you can configure **Google Cloud Storage** directly through the builder:

```java
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.FirestoreDatabaseRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

public class ArtifactReportingAgentApplication {

  public static void main(String[] args) {
    String appName = "report-generator-agent";
    BaseAgent reportAgent = initAgent();

    // 1. Initialize Firestore & GCS clients
    Firestore firestore = FirestoreOptions.getDefaultInstance().getService();
    Storage storage = StorageOptions.getDefaultInstance().getService();

    // 2. Build Runner with Firestore for sessions and GCS for persistent artifacts
    Runner runner =
        FirestoreDatabaseRunner.newBuilder()
            .agent(reportAgent)
            .appName(appName)
            .firestore(firestore)
            .withGcsArtifacts("my-agent-artifacts-bucket", storage)
            .build();

    // 3. Create session & run agent
    Session session =
        runner.sessionService().createSession(appName, "analyst-mg").blockingGet();

    Content request = Content.fromParts(Part.fromText("Generate the Q3 performance report PDF"));
    runner
        .runAsync(session.userId(), session.id(), request, RunConfig.builder().build())
        .blockingForEach(
            event -> {
              if (event.finalResponse()) {
                System.out.println("Result: " + event.stringifyContent());
              }
            });
  }

  private static BaseAgent initAgent() {
    return LlmAgent.builder()
        .name("report-generator-agent")
        .instruction("Generate and save analysis reports as artifacts.")
        .model("gemini-3.8-flash")
        .build();
  }
}
```

---

## Optional Configuration (`adk-firestore.properties`)

Default persistence settings can be configured via `adk-firestore.properties`:

- **`firestore.root.collection.name`**: Defaults to `"adk-session"`.
- **`keyword.extraction.stopwords`**: Defaults to a built-in set of common English stop words.
- **`gcs.adk.bucket.name`**: Google Cloud Storage bucket for artifact storage (can also be provided in code via `.withGcsArtifacts("bucket-name")`).

```properties
# Optional: customize root collection name (default: adk-session)
firestore.root.collection.name=custom-adk-session

# Optional: GCS bucket name for persistent artifacts
gcs.adk.bucket.name=your-gcs-bucket-name

# Optional: customize stop words for memory search
keyword.extraction.stopwords=a,about,above,after,again,all,and,any,are,at,be,by,for,from,how,if,in,into,is,it,of,on,or,that,the,this,to,was,what,when,where,who,will,with
```

---

## Builder API Reference

`FirestoreDatabaseRunner.newBuilder()` supports the following configuration methods:

| Method                                              | Description                                                                      |
| --------------------------------------------------- | -------------------------------------------------------------------------------- |
| `.agent(BaseAgent)`                                 | The agent to run. Mutually exclusive with `.app()`.                              |
| `.app(App)`                                         | An `App` instance. Mutually exclusive with `.agent()`.                           |
| `.appName(String)`                                  | Override the application name (defaults to agent/app name).                      |
| `.firestore(Firestore)`                             | Firestore client for session and memory services.                                |
| `.withGcsArtifacts(String bucket)`                  | Configure GCS artifact storage with default credentials.                         |
| `.withGcsArtifacts(String bucket, Storage storage)` | Configure GCS artifact storage with an explicit `Storage` client.                |
| `.artifactService(BaseArtifactService)`             | Provide a custom artifact service (e.g., `InMemoryArtifactService` for testing). |
| `.sessionService(BaseSessionService)`               | Override the default `FirestoreSessionService`.                                  |
| `.memoryService(BaseMemoryService)`                 | Override the default `FirestoreMemoryService`.                                   |
| `.plugins(List<Plugin>)`                            | Add plugins to the runner.                                                       |
| `.instantSource(InstantSource)`                     | Custom timestamp source (useful for testing).                                    |
| `.uuidProvider(UuidProvider)`                       | Custom ID generator (useful for testing).                                        |
| `.scheduler(Scheduler)`                             | RxJava scheduler for async hand-offs.                                            |
| `.toRunnerBuilder()`                                | Returns a `Runner.Builder` for further customization before building.            |
| `.build()`                                          | Builds and returns the configured `Runner`.                                      |

> **Note**: If neither `.withGcsArtifacts()` nor `.artifactService()` is set, and `gcs.adk.bucket.name` is not configured, `.build()` defaults to using `InMemoryArtifactService` for artifacts. TThis lets text-based agents run without any Google Cloud Storage setup, though any artifacts stored will be ephemeral and lost across restarts or distributed servers. For durable artifact persistence in production, configure `.withGcsArtifacts(...)` or `.artifactService(...)`.
---

## Legacy Constructor API

The direct constructor style is still supported for backward compatibility:

```java
// Simple: GCS bucket must be set in adk-firestore.properties
FirestoreDatabaseRunner runner = new FirestoreDatabaseRunner(agent, firestore);

// With custom app name
FirestoreDatabaseRunner runner = new FirestoreDatabaseRunner(agent, appName, firestore);

// With plugins
FirestoreDatabaseRunner runner = new FirestoreDatabaseRunner(agent, appName, plugins, firestore);
```

For new implementations, use the builder API (FirestoreDatabaseRunner.newBuilder()), which provides explicit artifact storage configuration, custom service injection, and clearer validation errors.
