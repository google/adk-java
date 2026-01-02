# Database Session Service

JDBC-based session service implementation for ADK Java.

## Features

- **No ORM Dependencies**: Uses JDBC with HikariCP for connection pooling
- **Multi-Database Support**: PostgreSQL, MySQL, H2 (SQLite not supported)
- **Automatic Schema Management**: Flyway migrations handle table creation/updates
- **3-Tier State Storage**: Separate tables for app-level, user-level, and session-level state
- **Reactive API**: RxJava 3 Single/Maybe/Completable return types

## Dependencies

- **HikariCP**: High-performance JDBC connection pool
- **Flyway**: Database schema versioning and migration
- **Jackson**: JSON serialization for events and state
- **RxJava 3**: Reactive programming support

## Database Schema

The service creates and manages these tables:

- `app_states`: Application-level state (shared across all users)
- `user_states`: User-level state (shared across user's sessions)
- `sessions`: Individual session data
- `events`: Event history for each session

## Usage

```java
// Create service with database URL
String dbUrl = "jdbc:postgresql://localhost:5432/adk?user=postgres&password=secret";
try (DatabaseSessionService sessionService = new DatabaseSessionService(dbUrl)) {
    
    // Create a session
    Session session = sessionService.createSession(
        "myApp", 
        "user123", 
        new ConcurrentHashMap<>(), 
        null
    ).blockingGet();
    
    // Append an event
    Event event = Event.builder()
        .id(UUID.randomUUID().toString())
        .invocationId("inv-1")
        .timestamp(System.currentTimeMillis())
        .build();
        
    Event appendedEvent = sessionService.appendEvent(session, event).blockingGet();
}
```

## Supported Databases

- **PostgreSQL**: Full support with JSONB
  - URL: `jdbc:postgresql://host:port/database?user=...&password=...`
- **MySQL**: Full support with JSON
  - URL: `jdbc:mysql://host:port/database?user=...&password=...`
- **H2**: For testing and development
  - URL: `jdbc:h2:mem:testdb` or `jdbc:h2:file:./data/mydb`
- **Cloud Spanner**: Full support
  - URL: `jdbc:cloudspanner:/projects/PROJECT_ID/instances/INSTANCE_ID/databases/DATABASE_ID`
- **SQLite**: NOT supported (no UPSERT support)

## State Management

State is stored across three tables with merge priority:

1. **App State** (lowest priority): `app:key` prefix
2. **User State** (medium priority): `user:key` prefix  
3. **Session State** (highest priority): No prefix

When retrieving a session, states are merged: app → user → session (higher priority overwrites).

## Configuration

Optional properties can be passed to the constructor:

```java
Map<String, Object> props = new HashMap<>();
props.put("connectionTimeout", 30000);
props.put("maximumPoolSize", 10);

DatabaseSessionService service = new DatabaseSessionService(dbUrl, props);
```
