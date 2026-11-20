package dev.pagerewind.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Stands in for every service behind the gateway and records what reaches it. */
final class Downstream implements AutoCloseable {

    record Request(String method, String path, String authorization, String csrf, String contentLength, String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();

    Downstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var headers = exchange.getRequestHeaders();
            synchronized (requests) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        headers.getFirst("Authorization"), headers.getFirst(CsrfRelayFilter.HEADER),
                        headers.getFirst("Content-Length"), body));
            }
            byte[] page = ("<html><body>downstream " + exchange.getRequestURI().getPath() + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    Request last() {
        List<Request> all = requests();
        return all.isEmpty() ? null : all.getLast();
    }

    void reset() {
        synchronized (requests) {
            requests.clear();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
