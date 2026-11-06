# Changelog

## 1.1.0-SNAPSHOT

- Added PostgreSQL support intended for Neon.
- Added HikariCP connection pooling and bounded pool settings.
- Bundled PostgreSQL, MySQL and SQLite JDBC drivers in the Maven build.
- Replaced the single long-lived JDBC connection with pooled connections.
- Changed order creation to use the generated database ID, avoiding a race when a player creates orders close together.
- Added a reproducible Maven build and GitHub Actions workflow.
- Preserved the MySQL and SQLite configuration modes.
