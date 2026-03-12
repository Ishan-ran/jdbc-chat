# Advanced JDBC Chat

This lightweight Java CLI chat stores every message in a SQLite database using JDBC. It runs locally, keeps a persistent conversation archive, and lets you fetch recent posts or filter by handle.

## Requirements

- Java 17 or later
- Maven 3.9+

## Getting started

1. Build the fat jar:
   ```bash
   mvn package
   ```
2. Run it:
   ```bash
   java -jar target/advanced-chat-1.0-SNAPSHOT-jar-with-dependencies.jar
   ```
3. Launch the browser UI (receives data from the same SQLite backend):
   ```bash
   java -cp target/advanced-chat-1.0-SNAPSHOT-jar-with-dependencies.jar com.example.chat.ChatServer
   ```
   Then open [http://localhost:8080](http://localhost:8080) in your browser to post and search messages without the console.
3. When the prompt asks for your handle, type a name and hit enter. The app creates `~/.advanced-chat/chat.db` automatically and initializes the schema.
4. Use the menu to send messages, view the latest threads, or filter conversation history by handle.

## How it works

- `ChatDao` manages schema setup and all SQL operations via `DriverManager`.
- Every message is timestamped with `LocalDateTime.now()` so the history reflects real time.
-- Menu choices let you post a new message, list the last 10 messages, search by prefixes, or look at your own posts.
-- The new HTML interface is served from `ChatServer` and talks to the `/api/messages/*` endpoints so you can keep using the same JDBC-powered backend.

## Extending this app

1. Swap the SQLite dependency for another JDBC driver (PostgreSQL, MySQL, etc.) and update the `jdbcUrl` construction in `ChatDao`.
2. Add authentication by prompting for passwords and storing hashed credentials in a new table.
3. Build a simple web UI that talks to the database via a servlet, Spring Boot controller, or the existing `ChatServer` + HTML page.
