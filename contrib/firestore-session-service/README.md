## Firestore Session Service for ADK

This sub-module contains an implementation of a session service for the ADK (Agent Development Kit) that uses Google Firestore as the backend for storing session data. This allows developers to manage user sessions in a scalable and reliable manner using Firestore's NoSQL database capabilities.

## Getting Started

To integrate this Firestore session service into your ADK project, add the following dependencies to your project's build configuration: pom.xml for Maven or build.gradle for Gradle.

## Basic Setup

```xml
<dependencies>
    <!-- ADK Core -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>1.0.1-rc.1-SNAPSHOT</version>
    </dependency>
    <!-- Firestore Session Service -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk-firestore-session-service</artifactId>
        <version>1.0.1-rc.1-SNAPSHOT</version>
    </dependency>
</dependencies>
```

```gradle
dependencies {
    // ADK Core
    implementation 'com.google.adk:google-adk:1.0.1-rc.1-SNAPSHOT'
    // Firestore Session Service
    implementation 'com.google.adk:google-adk-firestore-session-service:1.0.1-rc.1-SNAPSHOT'
}
```

## Running the Service

You can customize your ADK application to use the Firestore session service by providing your own Firestore property settings, otherwise library will use the default settings.

Sample Property Settings:

```properties
# Firestore collection name for storing session data
adk.firestore.collection.name=adk-session
# Google Cloud Storage bucket name for artifact storage
adk.gcs.bucket.name=your-gcs-bucket-name
#stop words for keyword extraction
adk.stop.words=a,about,above,after,again,against,all,am,an,and,any,are,aren't,as,at,be,because,been,before,being,below,between,both,but,by,can't,cannot,could,couldn't,did,didn't,do,does,doesn't,doing,don't,down,during,each,few,for,from,further,had,hadn't,has,hasn't,have,haven't,having,he,he'd,he'll,he's,her,here,here's,hers,herself,him,himself,his,how,i,i'd,i'll,i'm,i've,if,in,into,is
```

Then, you can use the `FirestoreDatabaseRunner` to start your ADK application with Firestore session management:

```java
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.FirestoreDatabaseRunner;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.adk.events.Event;
import java.util.Scanner;
import static java.nio.charset.StandardCharsets.UTF_8;

/***
 *
 */
public class YourAgentApplication {

    public static void main(String[] args) {
        System.out.println("Starting YourAgentApplication...");


        RunConfig runConfig = RunConfig.builder().build();
        String appName = "hello-time-agent";

          BaseAgent timeAgent = initAgent();
        // Initialize Firestore
        FirestoreOptions firestoreOptions = FirestoreOptions.getDefaultInstance();
        Firestore firestore = firestoreOptions.getService();


        // Use FirestoreDatabaseRunner to persist session state
        FirestoreDatabaseRunner runner = new FirestoreDatabaseRunner(
                timeAgent,
                appName,
                firestore
        );



        Session session = new FirestoreSessionService(firestore)
                .createSession(appName,"user1234",null,"12345")
                .blockingGet();


                try (Scanner scanner = new Scanner(System.in, UTF_8)) {
            while (true) {
                System.out.print("\nYou > ");
                String userInput = scanner.nextLine();
                if ("quit".equalsIgnoreCase(userInput)) {
                    break;
                }

                Content userMsg = Content.fromParts(Part.fromText(userInput));
                Flowable<Event> events = runner.runAsync(session.userId(), session.id(), userMsg, runConfig);

                System.out.print("\nAgent > ");
                events.blockingForEach(event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent());
                    }
                });
            }
        }


    }

    /** Mock tool implementation */
    @Schema(description = "Get the current time for a given city")
    public static Map<String, String> getCurrentTime(
        @Schema(name = "city", description = "Name of the city to get the time for") String city) {
        return Map.of(
            "city", city,
            "forecast", "The time is 10:30am."
        );
    }
    private static BaseAgent initAgent() {
        return LlmAgent.builder()
            .name("hello-time-agent")
            .description("Tells the current time in a specified city")
            .instruction("""
                You are a helpful assistant that tells the current time in a city.
                Use the 'getCurrentTime' tool for this purpose.
                """)
            .model("gemini-3.1-pro-preview")
            .tools(FunctionTool.create(YourAgentApplication.class, "getCurrentTime"))
            .build();
    }

}
```
