-- Copyright 2026 Google LLC
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
-- Unless required by applicable law or agreed to in writing, software distributed under
-- the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
-- ANY KIND, either express or implied. See the License for the specific language
-- governing permissions and limitations under the License.

-- Prepares MySQL or MariaDB for the ADK Java database session service: a database of its own
-- and the users that use it. The statements are the same for both engines. Run it once, as an
-- administrator:
--
--   mysql   -h <host> -u root -p < mysql-setup.sql
--   mariadb -h <host> -u root -p < mysql-setup.sql
--
-- It creates no tables. Those come from DatabaseSessionService.migrate(), or from running the
-- module's mysql/ or mariadb/V1__create_session_schema.sql as adk_sessions; the README has
-- both ways.
--
-- Replace both passwords before use, and narrow '%' to the hosts the application connects from.

-- A database of its own keeps the tables, whose names are as plain as "sessions" and "events",
-- away from the application's. The tables set their own character set and collation; this
-- default only applies to anything else created here.
CREATE DATABASE adk_sessions CHARACTER SET utf8mb4;

-- The owner: creates the tables, so it is the user for migrate() and for running the schema
-- script by hand. It may also be the only user, used by the application as well.
CREATE USER 'adk_sessions'@'%' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON adk_sessions.* TO 'adk_sessions'@'%';

-- Optional: a second user for the application at run time. It reads and writes the rows and
-- cannot create, alter or drop a table. Leave this part out to use adk_sessions for everything.
CREATE USER 'adk_sessions_app'@'%' IDENTIFIED BY 'change-me-too';
GRANT SELECT, INSERT, UPDATE, DELETE ON adk_sessions.* TO 'adk_sessions_app'@'%';
