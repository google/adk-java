-- Copyright 2026 Google LLC
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
-- Unless required by applicable law or agreed to in writing, software distributed under
-- the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
-- ANY KIND, either express or implied. See the License for the specific language
-- governing permissions and limitations under the License.

-- Prepares PostgreSQL for the ADK Java database session service: a database of its own and
-- the roles that use it. Run it once, as a superuser, with psql:
--
--   psql -h <host> -U postgres -v ON_ERROR_STOP=1 -f postgresql-setup.sql
--
-- It creates no tables. Those come from DatabaseSessionService.migrate(), or from running the
-- module's postgresql/V1__create_session_schema.sql as adk_sessions; the README has both ways.
--
-- Replace both passwords before use.

-- The owner: creates the tables, so it is the role for migrate() and for running the schema
-- script by hand. It may also be the only role, used by the application as well.
CREATE ROLE adk_sessions LOGIN PASSWORD 'change-me';

-- A database of its own keeps the tables, whose names are as plain as "sessions" and "events",
-- away from the application's. template0 lets the encoding be UTF8 whatever the cluster default.
CREATE DATABASE adk_sessions OWNER adk_sessions ENCODING 'UTF8' TEMPLATE template0;

-- Optional: a second role for the application at run time. It reads and writes the rows and
-- cannot create, alter or drop a table. Leave this part out to use adk_sessions for everything.
CREATE ROLE adk_sessions_app LOGIN PASSWORD 'change-me-too';

\connect adk_sessions

-- Applies to the tables adk_sessions creates from now on, so run this script before the tables
-- exist. For tables that are already there, grant the same four rights with
--   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO adk_sessions_app;
ALTER DEFAULT PRIVILEGES FOR ROLE adk_sessions IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO adk_sessions_app;
