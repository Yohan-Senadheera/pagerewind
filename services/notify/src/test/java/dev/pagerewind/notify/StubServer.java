package dev.pagerewind.notify;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny HTTP server that records requests and returns scripted answers. Stands in for Telegram, the incident
 * service and the Keycloak token endpoint (paths ending in {@code /token} return {@link #ACCESS_TOKEN}).
 */
final class StubServer implements AutoCloseable {

    static final String ACCESS_TOKEN = "stub-access-token";

    record Request(String method, String path, String body, String authorization) {
    }

    private record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private final Map<String, Deque<Reply>> scripted = new ConcurrentHashMap<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(new Request(exchange.getRequestMethod(), path, body,
                        exchange.getRequestHeaders().getFirst("Authorization")));
            }
            Reply reply = nextReply(path);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private Reply nextReply(String path) {
        for (Map.Entry<String, Deque<Reply>> entry : scripted.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                synchronized (entry.getValue()) {
                    Reply reply = entry.getValue().poll();
                    if (reply != null) {
                        return reply;
                    }
                }
            }
        }
        if (path.endsWith("/token")) {
            return new Reply(200, "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        }
        return new Reply(200, "{\"ok\":true,\"result\":true}");
    }

    /** The next calls to a path ending with {@code pathSuffix} get these answers, in order. */
    void script(String pathSuffix, int status, String body) {
        scripted.computeIfAbsent(pathSuffix, k -> new ArrayDeque<>()).add(new Reply(status, body));
    }

    List<Request> requests(String pathPart) {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path().contains(pathPart)).toList();
        }
    }

    void reset() {
        synchronized (requests) {
            requests.clear();
        }
        scripted.clear();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
