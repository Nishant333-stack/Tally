package dev.nishant.tally;

import dev.nishant.tally.http.Json;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dependency-free test harness: runs every public static void test*() method in the
 * given classes against a freshly created Postgres database and a live server.
 */
public final class TestKit {
    public static App app;
    public static String base;
    public static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public record Res(int status, Map<String, Object> body, HttpResponse<String> raw) {
        @SuppressWarnings("unchecked")
        public <T> T get(String key) { return (T) body.get(key); }
    }

    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String admin = env("TEST_DB_ADMIN_URL", "jdbc:postgresql://127.0.0.1:5432/postgres");
        try (Connection c = DriverManager.getConnection(admin, "postgres", ""); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS tally_test WITH (FORCE)");
            st.execute("CREATE DATABASE tally_test");
        }
        app = new App(new App.Config("jdbc:postgresql://127.0.0.1:5432/tally_test", "postgres", "", 20, 0,
                Path.of("db/migrations"), null)).start();
        base = "http://127.0.0.1:" + app.port();

        Class<?>[] suites = { JsonTest.class, ApiTest.class, ConcurrencyTest.class };
        int passed = 0;
        List<String> failed = new ArrayList<>();
        long t0 = System.nanoTime();
        for (Class<?> suite : suites) {
            for (Method m : suite.getDeclaredMethods()) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers())) continue;
                String name = suite.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(null);
                    passed++;
                    System.out.println("  PASS " + name);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    failed.add(name);
                    System.out.println("  FAIL " + name + " -> " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                }
            }
        }
        app.close();
        System.out.printf("%n%d passed, %d failed (%.1fs)%n", passed, failed.size(), (System.nanoTime() - t0) / 1e9);
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    // ---------------------------------------------------------------- HTTP helpers

    public static Res get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    public static Res post(String path, Object body) throws Exception { return post(path, body, null); }

    public static Res post(String path, Object body, String idemKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : Json.write(body)));
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return send(b.build());
    }

    private static Res send(HttpRequest req) throws Exception {
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), Json.parseObject(r.body()), r);
    }

    public static String newAccount(String name) throws Exception {
        Res r = post("/api/accounts", Map.of("ownerName", name, "email", name.toLowerCase() + "+" + System.nanoTime() + "@example.com"));
        eq(201, r.status(), "create account");
        return r.get("id");
    }

    public static void fund(String accountId, long amount) throws Exception {
        Res r = post("/api/accounts/" + accountId + "/deposits", Map.of("amount", amount), "dep-" + System.nanoTime() + Math.random());
        eq(201, r.status(), "deposit");
    }

    public static long balance(String accountId) throws Exception {
        return ((Number) get("/api/accounts/" + accountId).body().get("balance")).longValue();
    }

    public static void assertReconciled() throws Exception {
        Res r = get("/api/admin/reconciliation");
        check(Boolean.TRUE.equals(r.get("ok")), "ledger reconciliation failed: " + r.body());
    }

    // ---------------------------------------------------------------- assertions

    public static void eq(Object expected, Object actual, String what) {
        boolean same = expected instanceof Number e && actual instanceof Number a
                ? e.longValue() == a.longValue()
                : Objects.equals(expected, actual);
        if (!same) throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    public static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    @SuppressWarnings("unchecked")
    public static String errorCode(Res r) {
        return (String) ((Map<String, Object>) r.body().get("error")).get("code");
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null ? d : v;
    }
}
