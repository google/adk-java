# MySQL Session Service for ADK

This sub-module contains an implementation of a session service for the ADK (Agent Development Kit) that uses MySQL as the backend for storing session data. This allows developers to manage user sessions in a scalable and reliable manner using MySQL's relational database capabilities and JSON column support.

## Getting Started

To integrate this MySQL session service into your ADK project, add the following dependencies to your project's build configuration.

### Maven

```xml
<dependencies>
    <!-- ADK Core -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>0.4.1-SNAPSHOT</version>
    </dependency>
    <!-- MySQL Session Service -->
    <dependency>
        <groupId>com.google.adk.contrib</groupId>
        <artifactId>google-adk-mysql-session-service</artifactId>
        <version>0.4.1-SNAPSHOT</version>
    </dependency>
    <!-- MySQL Connector (or your preferred driver) -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <version>8.3.0</version>
    </dependency>
    <!-- HikariCP (Recommended for connection pooling) -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>
</dependencies>
```

## Database Schema

You must create the following tables in your MySQL database (version 8.0+ required for JSON support).

```sql
CREATE TABLE IF NOT EXISTS adk_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    app_name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    state JSON,
    created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_app_user (app_name, user_id)
);

CREATE TABLE IF NOT EXISTS adk_events (
    event_id VARCHAR(255) PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    event_data JSON,
    created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (session_id) REFERENCES adk_sessions(session_id) ON DELETE CASCADE,
    INDEX idx_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS adk_app_state (
    app_name VARCHAR(255) NOT NULL,
    state_key VARCHAR(768) NOT NULL,
    state_value JSON,
    PRIMARY KEY (app_name, state_key)
);

CREATE TABLE IF NOT EXISTS adk_user_state (
    app_name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(768) NOT NULL,
    state_value JSON,
    PRIMARY KEY (app_name, user_id, state_key)
);
```

## Usage

You can configure the `MySqlSessionService` by passing a `DataSource`. We recommend using HikariCP for production connection pooling.

```java
import com.google.adk.runner.Runner;
import com.google.adk.sessions.MySqlSessionService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public class MyApp {
    public static void main(String[] args) {
        // 1. Configure Data Source
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/my_db");
        config.setUsername("user");
        config.setPassword("password");
        DataSource dataSource = new HikariDataSource(config);

        // 2. Create the Session Service
        MySqlSessionService sessionService = new MySqlSessionService(dataSource);

        // 3. Pass it to the Runner
        Runner runner = new Runner(
            new MyAgent(), 
            "MyAppName", 
            new InMemoryArtifactService(), // or GcsArtifactService
            sessionService,                // <--- Your MySQL Service
            new InMemoryMemoryService()    // or FirestoreMemoryService
        );
        
        runner.runLive();
    }
}
```

## Testing

This module includes both unit tests and integration tests.

*   **Unit Tests:** Fast, in-memory tests that mock the database connection. These verify the service logic without requiring a running database.
    ```bash
    mvn test
    ```

*   **Integration Tests:** Comprehensive tests that run against a real MySQL instance using [Testcontainers](https://testcontainers.com/). These require a Docker environment (e.g., Docker Desktop, or a CI runner with Docker support).
    ```bash
    mvn verify
    ```

