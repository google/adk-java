# MySQL Session Service for ADK

This module provides a MySQL-backed implementation of ADK's `BaseSessionService`. It persists
sessions, events, and app- and user-scoped state while keeping the MySQL JDBC driver and connection
pool under the application's control.

## Dependency

Use the same version for ADK core and this module. The MySQL driver and a production connection pool
are intentionally not forced on consumers; add the implementations used by your application.

<!-- x-release-please-released-start-version -->

```xml
<properties>
    <adk.version>1.12.0</adk.version>
</properties>
```

<!-- x-release-please-released-end -->

```xml
<dependencies>
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>${adk.version}</version>
    </dependency>
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk-mysql-session-service</artifactId>
        <version>${adk.version}</version>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <version>9.6.0</version>
    </dependency>
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>7.0.2</version>
    </dependency>
</dependencies>
```

The version above tracks ADK releases; until this contribution is released, build this module and
ADK core together from the PR branch and use its snapshot version. Connector/J 9.6 retains support
for MySQL 8.0. Applications using newer drivers should check their minimum supported server version.

## Database schema

MySQL 8.0 or newer is required. Apply the packaged
[`mysql-session-service-schema.sql`](src/main/resources/mysql-session-service-schema.sql) before
constructing the service.

The session identity is the ADK `SessionKey` tuple `(app_name, user_id, session_id)`, so the same
client-generated session ID can safely be reused in a different app or user scope. Events use an
internal monotonic sequence to provide deterministic ordering when multiple events have the same
millisecond timestamp.

The schema in earlier preview revisions of this contribution used globally unique `session_id` and
`event_id` primary keys. Recreate or migrate those tables before upgrading to this schema.

## Usage

Configure a `DataSource`, construct the service, and pass it to the current `Runner` builder.

```java
import com.google.adk.runner.Runner;
import com.google.adk.sessions.MySqlSessionService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://localhost:3306/my_db");
config.setUsername("user");
config.setPassword("password");
HikariDataSource dataSource = new HikariDataSource(config);

MySqlSessionService sessionService = new MySqlSessionService(dataSource);
Runner runner =
    Runner.builder()
        .agent(myAgent)
        .appName("my-app")
        .sessionService(sessionService)
        .build();
```

App-prefixed (`app:`) and user-prefixed (`user:`) values supplied when creating a session are
persisted in their respective shared state scopes. Temporary (`temp:`) values are returned on the
newly created session but are intentionally not persisted and therefore do not appear after a
reload.

`appendEvent` is lazy: subscribe to the returned `Single` (or use `blockingGet`) to persist the event.
It updates the caller's session state and event list only after the database transaction commits.
A stale session fails with `ConcurrentModificationException`; reload it before retrying.

## Testing

Unit and contract tests use H2 in MySQL compatibility mode and require no external services:

```bash
./mvnw -pl contrib/mysql-session-service -am test
```

The integration profile runs the `*IT` tests against a real MySQL container and therefore requires
Docker:

```bash
./mvnw -pl contrib/mysql-session-service -am -Pintegration-tests verify
```

The same contract tests run against H2 and MySQL, including event JSON round trips, scoped state,
timestamp precision, and rollback without mutating the caller's session. The container defaults to
MySQL 8.4; select MySQL 8.0 with `-Dmysql.image=mysql:8.0`. A running Docker Engine compatible with
Testcontainers 2.x is required.
