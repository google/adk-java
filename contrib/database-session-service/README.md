# Database Session Service for ADK Java

A JDBC `BaseSessionService` for SQLite, PostgreSQL, MySQL, and MariaDB. It follows
Java session behavior and uses the [Python v1 schema](https://github.com/google/adk-python/blob/098eefa2466d923cf5fec10b297057e012069d89/src/google/adk/sessions/schemas/v1.py)
as its relational layout. SQL Server and Spanner are outside this first version.

## Getting started

### 1. Add the module

Keep this module and ADK core on the same version. Java 17 or later.

<!-- x-release-please-start-version -->

```xml
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk-database-session-service</artifactId>
  <version>1.14.1-SNAPSHOT</version>
</dependency>
```

```gradle
implementation 'com.google.adk:google-adk-database-session-service:1.14.1-SNAPSHOT'
```

<!-- x-release-please-end -->

Add the driver for your database and, if running migrations, its Flyway plugin
on the same version as Flyway core (verified with 12.4.0).

| Database | JDBC driver | Flyway plugin |
| --- | --- | --- |
| SQLite | `org.xerial:sqlite-jdbc` | Included in Flyway core |
| PostgreSQL | `org.postgresql:postgresql` | `org.flywaydb:flyway-database-postgresql` |
| MySQL | `com.mysql:mysql-connector-j` | `org.flywaydb:flyway-mysql` |
| MariaDB | `org.mariadb.jdbc:mariadb-java-client` | `org.flywaydb:flyway-mysql` |

### 2. Prepare a database

Give the service a database, or at least a schema, of its own, and its own
`DataSource`. Neither is enforced, and both are worth it:

- The tables are named as in the Python v1 schema: `sessions`, `events`,
  `app_states`, `user_states` and `adk_internal_metadata`. Four of those names
  are plain enough for an application to use already, and there is no prefix
  option. The migration stops when it finds tables under these names that it
  did not create; it never adopts or changes them.
- The service needs connections with auto-commit on and blocks the calling
  thread while it uses them. With its own pool, its settings and its load stay
  apart from the application's.

Sharing the application's `DataSource` works as long as its connections have
auto-commit on and its schema holds no tables with the names above.

| Database | Prepare with | JDBC URL |
| --- | --- | --- |
| PostgreSQL | [`sql/postgresql-setup.sql`](sql/postgresql-setup.sql), as a superuser | `jdbc:postgresql://<host>:5432/adk_sessions` |
| MySQL | [`sql/mysql-setup.sql`](sql/mysql-setup.sql), as an administrator | `jdbc:mysql://<host>:3306/adk_sessions` |
| MariaDB | the same [`sql/mysql-setup.sql`](sql/mysql-setup.sql) | `jdbc:mariadb://<host>:3306/adk_sessions` |
| SQLite | nothing; the file is created on first use | `jdbc:sqlite:/var/lib/my-app/adk-sessions.db` |

Each script creates the database `adk_sessions` and two accounts, and no
tables. `adk_sessions` owns the database and creates the tables.
`adk_sessions_app` is optional: an account for the running application that
can read and write the rows (`SELECT`, `INSERT`, `UPDATE`, `DELETE`, which is
all the service uses at run time) and cannot change the tables. Replace the
passwords in the scripts before running them.

To keep the tables inside a PostgreSQL database you already have, create a
schema instead of a database and name it in the URL:

```sql
CREATE SCHEMA adk AUTHORIZATION adk_sessions;
-- Only with the optional run-time account:
GRANT USAGE ON SCHEMA adk TO adk_sessions_app;
ALTER DEFAULT PRIVILEGES FOR ROLE adk_sessions IN SCHEMA adk
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO adk_sessions_app;
```

```
jdbc:postgresql://<host>:5432/<your-database>?currentSchema=adk
```

In MySQL and MariaDB a schema is a database, so the dedicated database is the
way to separate the tables. For SQLite use a file; a per-connection `:memory:`
database does not persist across borrows.

MySQL needs 8.0.17 or later, for the `utf8mb4_0900_bin` collation of the key
columns. The versions the module is tested with are listed under
[Verification](#verification).

### 3. Create the tables

Either let the module do it, connected as the owner:

```java
DatabaseSessionService sessions = new DatabaseSessionService(ownerDataSource);
sessions.migrate().blockingAwait(); // One migration owner, before starting workers.
```

`migrate()` can be repeated; on an up-to-date schema it changes nothing. A
service built with `.autoMigrate(true)` runs it before its first operation
instead. Migrations use only the ADK migration locations and
`adk_schema_history`; they neither scan application migrations nor adopt
foreign or partial tables. Run them before concurrent workers start.

Or run the schema script yourself, as the owner. There is one per database, in
this repository under
[`src/main/resources/com/google/adk/sessions/migration`](src/main/resources/com/google/adk/sessions/migration)
and at the same path in the module's jar:

```shell
unzip -p google-adk-database-session-service-<version>.jar \
  com/google/adk/sessions/migration/postgresql/V1__create_session_schema.sql > schema.sql

psql    -h <host> -U adk_sessions -d adk_sessions -v ON_ERROR_STOP=1 -f schema.sql
mysql   -h <host> -u adk_sessions -p adk_sessions < schema.sql    # from migration/mysql
mariadb -h <host> -u adk_sessions -p adk_sessions < schema.sql    # from migration/mariadb
sqlite3 /var/lib/my-app/adk-sessions.db < schema.sql              # from migration/sqlite
```

The script is the same SQL that `migrate()` runs, but it leaves no Flyway
history. Choose one of the two ways and stay with it: on tables created by
hand, keep auto-migration off and do not call `migrate()`, which reports them
as a partial schema. Apply the scripts of later versions by hand as well.

### 4. Connect and use it

The service takes any `DataSource`. The module reads no environment variables,
system properties or configuration files; where the URL and the credentials
come from is up to the application. With HikariCP, for example:

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl(System.getenv("ADK_SESSIONS_JDBC_URL")); // the application's own names
config.setUsername(System.getenv("ADK_SESSIONS_DB_USER"));
config.setPassword(System.getenv("ADK_SESSIONS_DB_PASSWORD"));
config.setMaximumPoolSize(10);
DataSource dataSource = new HikariDataSource(config);

DatabaseSessionService sessions = new DatabaseSessionService(dataSource);

Runner runner =
    Runner.builder().appName("my-app").agent(yourAgent).sessionService(sessions).build();
```

Your application owns the `DataSource`, pool, and timeouts, and closes them.
Construction performs no I/O. Operations are cold and JDBC runs on the
subscribing thread; use an application-owned scheduler if needed.

Use independently borrowed auto-commit connections, writable for writes. A
connection that is already inside a transaction, such as one from a pool
configured with auto-commit off, is refused before anything runs on it. The
service runs its own short transactions and does not join one of the caller's.

The builder has the optional settings:

| Setting | Default | Purpose |
| --- | --- | --- |
| `dialect(Dialect)` | detected from JDBC metadata on first use | Skip detection, or name the dialect of a compatible database. |
| `autoMigrate(boolean)` | `false` | Migrate before the first operation instead of calling `migrate()`. |
| `statementTimeout(Duration)` | 15 seconds | Limit for each statement, and for SQLite's wait on a competing writer. Not an end-to-end deadline: one operation runs several statements. Connection and network timeouts belong to the `DataSource`. |
| `instantSource(InstantSource)` | system clock | Creation time of a session. |
| `uuidProvider(UuidProvider)` | random UUID | ID of a session created without one. |

```java
DatabaseSessionService sessions =
    DatabaseSessionService.builder(dataSource)
        .dialect(DatabaseSessionService.Dialect.POSTGRESQL)
        .statementTimeout(Duration.ofSeconds(30))
        .build();
```

### Spring Boot

Declare the service as a bean over a `DataSource`:

```java
@Configuration
class SessionServiceConfiguration {
  @Bean
  DatabaseSessionService sessionService(DataSource dataSource) {
    DatabaseSessionService sessions = new DatabaseSessionService(dataSource);
    sessions.migrate().blockingAwait();
    return sessions;
  }
}
```

- The module's migration and the application's own Flyway migrations do not
  touch each other: they use different script locations and history tables.
- Leave `spring.datasource.hikari.auto-commit` at its default, `true`.
- For a database of its own, give the service a `DataSource` other than the
  application's primary one.

## Behavior

| Operation | JDBC behavior and comparison |
| --- | --- |
| Create | As in InMemory, a blank session ID asks for a generated one; any other ID is kept exactly as given. A duplicate ID is rejected with `SESSION_ALREADY_EXISTS`. Initial `app:`/`user:` keys go to the shared state, as in Python and unlike InMemory. |
| Append | Like InMemory, partial events are no-ops and history follows append order, even when timestamps tie or go backwards. An event ID must be unique within its session, as in Python v1 and unlike InMemory. |
| Get | As in InMemory, take the last N appends, then apply the inclusive time cursor. N=0 returns no history; a missing session returns an empty `Maybe`. |
| List | Returns state/metadata without loading events, ordered by update time then ID. |
| Delete / list events | A missing session throws `SessionNotFoundException` per the Java interface Javadocs. Deleting a session cascades its events but preserves shared state. |
| State | App/user state is shared, local state is per session, and `temp:` is never persisted (including event deltas). A committed append applies only its delta to the supplied session; as in InMemory that includes its `temp:` keys, so later steps of the same invocation can read them, and they are gone on reload. Other sessions' shared changes are visible on reload. |

**Append needs the current tail.** The transaction compares the supplied last
event ID with the stored tail, or requires both histories to be empty. Reload
before appending a stale or filtered snapshot that lacks the tail. Detached
copies with the tail work without any extra core fields. Do not reuse snapshots
after delete/recreate: empty histories and reused tail IDs cannot distinguish
the old session from the new one.

Only after commit and connection cleanup succeed does the service update the
supplied session. Do not mutate the submitted event/delta while it is being
persisted. If commit acknowledgement is lost, or cleanup fails after commit,
reload before retrying. Errors distinguish these outcomes without exposing raw
driver messages, credentials, or payloads.

When the database itself gives up on a transaction because of a concurrent one
(a deadlock victim, a serialization failure, or a lock wait that timed out),
the error says that nothing was written and the same call can simply be
repeated. Every failure is logged at WARN with its operation, phase, SQLSTATE
and vendor code. The driver's own exception, whose message may quote SQL or
data, is logged only at DEBUG for
`com.google.adk.sessions.DatabaseDiagnostics`.

## Storage

The five Python v1 tables, scoped primary keys, and
`idx_events_app_user_session_ts_id` are retained, under their Python names.
Java adds a per-session `event_sequence` for append order, its storage-format
marker, explicit key collations and validation checks, and Flyway history.

- Zone-free UTC SQL times have six fractional digits (fixed-width text on
  SQLite), with a common year range of 1000–9999. Session creation time is
  truncated to microseconds; event timestamps retain Java milliseconds.
  App/user update times come from the database UTC clock.
- Identifiers use exact `VARCHAR(128)` comparisons (C/BINARY or a MySQL/MariaDB
  binary NO PAD collation). Case, accents, and trailing spaces are significant.
  MySQL/MariaDB require InnoDB pages of at least 16 KiB for the composite keys.
- The complete Java event is stored as JSON: PostgreSQL `json`, MySQL/MariaDB
  validated LONGTEXT, and SQLite validated text. The `invocation_id` column is
  only a copy (empty when absent, at most 256 code points). Java always writes
  `event_data`; a NULL payload cannot be decoded.

**Do not share the database with Python or another ADK SDK.** Java requires both
`schema_version=1` and `adk_java_storage_format=jdbc-v1` and rejects a
Python-created schema. Python may still accept Java's numeric version and read
Java event milliseconds as seconds. No conversion from the former MySQL-only
contribution is included.

## Verification

From the repository root, with Docker running for the integration profile:

```shell
./mvnw -pl core -am -DskipTests install
./mvnw -pl contrib/database-session-service test
./mvnw -pl contrib/database-session-service '-Pintegration-tests,consumer-tests' '-Dadk.test.backends=postgresql,mysql,mariadb,postgresql18,mariadb114' verify
```

The same contract runs on SQLite default/WAL and real PostgreSQL 17.10/18.4,
MySQL 8.4.11, and MariaDB 11.8.7/11.4.12 containers. Its part that describes
behavior shared with `InMemorySessionService` also runs against that reference
in core. Docker is required, not silently skipped. Consumer tests also cover a
standalone Spring Boot/Flyway application with separate migrations. Reports are
in the module's `target`.
