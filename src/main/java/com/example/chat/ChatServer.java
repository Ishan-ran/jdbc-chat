package com.example.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ChatServer {
    private static final int PORT = 8080;
    private static final Gson GSON = new Gson();

    private ChatServer() {
    }

    public static void main(String[] args) {
        Path store = Paths.get(System.getProperty("user.home"), ".advanced-chat", "chat.db");
        try {
            Files.createDirectories(store.getParent());
            ChatDao dao = new ChatDao(store);
            dao.initSchema();
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", new StaticHandler("/public/index.html", "text/html; charset=UTF-8"));
            server.createContext("/api/messages/recent", exchange -> handleRead(exchange, (params, limit) -> dao.fetchRecent(limit), 10));
            server.createContext("/api/messages/search", exchange -> handleRead(exchange, (params, limit) -> dao.fetchByAuthor(params.getOrDefault("prefix", ""), limit), 15));
            server.createContext("/api/messages/my", exchange -> handleRead(exchange, (params, limit) -> dao.fetchByAuthor(params.getOrDefault("author", ""), limit), 5));
            server.createContext("/api/messages/send", exchange -> handleSend(exchange, dao));
            server.start();
            System.out.println("Chat server is listening on http://localhost:" + PORT);
        } catch (Exception ex) {
            System.err.println("Failed to start server: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static void handleRead(HttpExchange exchange, MessageSupplier supplier, int defaultLimit) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Only GET allowed");
            return;
        }
        Map<String, String> params = queryParams(exchange);
        int limit = requestedLimit(params, defaultLimit);
        try {
            List<Message> messages = supplier.get(params, limit);
            sendJson(exchange, messagesToJson(messages));
        } catch (Exception ex) {
            sendPlain(exchange, 500, "Unable to load messages");
        }
    }

    private static int requestedLimit(Map<String, String> params, int defaultLimit) {
        if (defaultLimit <= 0) {
            return 0;
        }
        String value = params.get("limit");
        if (value == null || value.isEmpty()) {
            return defaultLimit;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultLimit;
        }
    }

    private static void handleSend(HttpExchange exchange, ChatDao dao) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Only POST allowed");
            return;
        }
        String body = readBody(exchange);
        SendPayload payload;
        try {
            payload = GSON.fromJson(body, SendPayload.class);
        } catch (Exception ex) {
            sendPlain(exchange, 400, "Malformed payload");
            return;
        }
        if (payload == null || payload.author == null || payload.author.isBlank() || payload.text == null || payload.text.isBlank()) {
            sendPlain(exchange, 400, "Author and text are required");
            return;
        }
        try {
            dao.storeMessage(payload.author.trim(), payload.text.trim());
            List<Message> latest = dao.fetchByAuthor(payload.author.trim(), 1);
            sendJson(exchange, messagesToJson(latest));
        } catch (Exception ex) {
            sendPlain(exchange, 500, "Unable to store message");
        }
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx == -1) {
                continue;
            }
            String name = decode(part.substring(0, idx));
            String value = decode(part.substring(idx + 1));
            params.put(name, value);
        }
        return params;
    }

    private static String decode(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static JsonArray messagesToJson(List<Message> messages) {
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject json = new JsonObject();
            json.addProperty("id", message.getId());
            json.addProperty("author", message.getAuthor());
            json.addProperty("text", message.getText());
            json.addProperty("timestamp", message.getTimestamp().toString());
            array.add(json);
        }
        return array;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            is.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, JsonArray body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, 200, "application/json; charset=UTF-8", bytes);
    }

    private static void sendPlain(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, status, "text/plain; charset=UTF-8", bytes);
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static class StaticHandler implements HttpHandler {
        private final String resourcePath;
        private final String contentType;

        StaticHandler(String resourcePath, String contentType) {
            this.resourcePath = resourcePath;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendPlain(exchange, 405, "Only GET allowed");
                return;
            }
            try (InputStream resource = ChatServer.class.getResourceAsStream(resourcePath)) {
                if (resource == null) {
                    sendPlain(exchange, 404, "Not found");
                    return;
                }
                byte[] bytes = resource.readAllBytes();
                sendResponse(exchange, 200, contentType, bytes);
            }
        }
    }

    @FunctionalInterface
    private interface MessageSupplier {
        List<Message> get(Map<String, String> params, int limit) throws Exception;
    }

    private static final class SendPayload {
        String author;
        String text;
    }
}