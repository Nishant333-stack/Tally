package dev.nishant.tally.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Path-pattern router over the JDK HttpServer. Patterns use {name} segments,
 * e.g. "/accounts/{id}/ledger". Handles JSON errors, CORS, request-size limits,
 * and serves the built React app for any non-API GET.
 */
public final class Router implements HttpHandler {
    private static final Logger LOG = Logger.getLogger("tally.http");
    private static final int MAX_BODY_BYTES = 64 * 1024;

    public record Request(String method, String path, Map<String, String> params,
                          Map<String, String> query, HttpExchange exchange, String body) {
        public String header(String name) { return exchange.getRequestHeaders().getFirst(name); }

        public Map<String, Object> json() {
            if (body == null || body.isBlank()) throw ApiException.badRequest("request body must be a JSON object");
            try {
                return Json.parseObject(body);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }
    }

    public record Response(int status, Object body) {
        public static Response ok(Object body) { return new Response(200, body); }
        public static Response created(Object body) { return new Response(201, body); }
    }

    @FunctionalInterface
    public interface Handler { Response handle(Request r) throws Exception; }

    private record Route(String method, String[] segments, Handler handler) { }

    private final List<Route> routes = new ArrayList<>();
    private final Path staticDir;

    public Router(Path staticDir) { this.staticDir = staticDir; }

    public Router on(String method, String pattern, Handler h) {
        routes.add(new Route(method, split(pattern), h));
        return this;
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? new String[0] : p.split("/");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        long start = System.nanoTime();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        int status;
        try {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if (method.equals("OPTIONS")) {
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
                ex.sendResponseHeaders(204, -1);
                status = 204;
            } else {
                status = dispatch(ex, method, path);
            }
        } finally {
            ex.close();
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("%s %s -> %d (%.1f ms)", method, path, status, (System.nanoTime() - start) / 1e6));
        }
    }

    private int dispatch(HttpExchange ex, String method, String path) throws IOException {
        String[] parts = split(path);
        boolean pathMatched = false;
        for (Route r : routes) {
            Map<String, String> params = match(r.segments, parts);
            if (params == null) continue;
            pathMatched = true;
            if (!r.method.equals(method)) continue;
            try {
                String body = readBody(ex);
                Response res = r.handler.handle(new Request(method, path, params, query(ex), ex, body));
                return send(ex, res.status(), res.body());
            } catch (ApiException e) {
                return sendError(ex, e.status, e.code, e.getMessage());
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "unhandled error on " + method + " " + path, e);
                return sendError(ex, 500, "internal_error", "unexpected server error");
            }
        }
        if (pathMatched) return sendError(ex, 405, "method_not_allowed", method + " not allowed on " + path);
        if (method.equals("GET") && !path.startsWith("/api/")) return serveStatic(ex, path);
        return sendError(ex, 404, "not_found", "no route for " + method + " " + path);
    }

    private static Map<String, String> match(String[] pattern, String[] parts) {
        if (pattern.length != parts.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                params.put(p.substring(1, p.length() - 1), URLDecoder.decode(parts[i], StandardCharsets.UTF_8));
            } else if (!p.equals(parts[i])) {
                return null;
            }
        }
        return params;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            q.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                  URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(MAX_BODY_BYTES + 1);
            if (buf.length > MAX_BODY_BYTES) throw new ApiException(413, "payload_too_large", "body exceeds 64 KiB");
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static int send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        return status;
    }

    static int sendError(HttpExchange ex, int status, String code, String message) throws IOException {
        Map<String, Object> err = new HashMap<>();
        err.put("code", code);
        err.put("message", message);
        return send(ex, status, Map.of("error", err));
    }

    private int serveStatic(HttpExchange ex, String path) throws IOException {
        if (staticDir == null || !Files.isDirectory(staticDir)) return sendError(ex, 404, "not_found", "no route for GET " + path);
        Path root = staticDir.toAbsolutePath().normalize();
        Path file = root.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!file.startsWith(root)) return sendError(ex, 404, "not_found", "not found");
        if (!Files.isRegularFile(file)) file = root.resolve("index.html"); // SPA fallback
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        return 200;
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
