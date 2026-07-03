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
        <version>0.4.0-SNAPSHOT</version>
    </dependency>
    <!-- Firestore Session Service -->
    <dependency>
        <groupId>com.google.adk.contrib</groupId>
        <artifactId>firestore-session-service</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

```gradle
dependencies {
    // ADK Core
    implementation 'com.google.adk:google-adk:0.4.0-SNAPSHOT'
    // Firestore Session Service
    implementation 'com.google.adk.contrib:firestore-session-service:0.4.0-SNAPSHOT'
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
import com.google.adk.agents.YourAgent; // Replace with your actual agent class
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.FirestoreDatabaseRunner;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import java.util.ArrayList;
import java.util.List;
import com.google.adk.sessions.GetSessionConfig;
import java.util.Optional;




public class YourApp {
    public static void main(String[] args) {
        Firestore firestore = FirestoreOptions.getDefaultInstance().getService();
        List<BasePlugin> plugins = new ArrayList<>();
        // Add any plugins you want to use


        FirestoreDatabaseRunner firestoreRunner = new FirestoreDatabaseRunner(
            new YourAgent(), // Replace with your actual agent instance
            "YourAppName",
            plugins,
            firestore
        );

        GetSessionConfig config = GetSessionConfig.builder().build();
        // Example usage of session service
        firestoreRunner.sessionService().getSession("APP_NAME","USER_ID","SESSION_ID", Optional.of(config));

    }
}
```

Make sure to replace `YourAgent` and `"YourAppName"` with your actual agent class and application name.
