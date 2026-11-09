import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closed-loop load generator for POST /api/transfers.
 * Usage: java -cp build/test-classes LoadTest [baseUrl] [wallets] [clients] [seconds]
 */
public final class LoadTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        int wallets = args.length > 1 ? Integer.parseInt(args[1]) : 1_000;
        int clients = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 30;

        String run = Long.toString(System.currentTimeMillis(), 36);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < wallets; i++) {
            String id = extractId(post(base + "/api/accounts",
                    "{\"ownerName\":\"Load " + i + "\",\"email\":\"load" + i + "." + run + "@example.com\"}", null).body());
            post(base + "/api/accounts/" + id + "/deposits", "{\"amount\":100000000}", "seed-" + run + "-" + i);
            ids.add(id);
        }
        System.out.printf("seeded %d wallets; running %d clients for %ds%n", wallets, clients, seconds);

        long[][] lat = new long[clients][];
        int[] counts = new int[clients];
        AtomicInteger errors = new AtomicInteger(), seq = new AtomicInteger();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int c = 0; c < clients; c++) {
                final int me = c;
                pool.submit(() -> {
                    long[] mine = new long[1 << 20];
                    int n = 0;
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (System.nanoTime() < end) {
                        int x = rnd.nextInt(wallets), y = (x + 1 + rnd.nextInt(wallets - 1)) % wallets;
                        String body = "{\"fromAccountId\":\"" + ids.get(x) + "\",\"toAccountId\":\"" + ids.get(y)
                                + "\",\"amount\":" + (1 + rnd.nextInt(10_000)) + "}";
                        long t0 = System.nanoTime();
                        try {
                            int s = post(base + "/api/transfers", body, run + "-" + seq.incrementAndGet()).statusCode();
                            if (s != 201) errors.incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                        if (n < mine.length) mine[n++] = System.nanoTime() - t0;
                    }
                    lat[me] = mine;
                    counts[me] = n;
                    return null;
                });
            }
        }
        int total = Arrays.stream(counts).sum();
        long[] all = new long[total];
        int k = 0;
        for (int c = 0; c < clients; c++) { System.arraycopy(lat[c], 0, all, k, counts[c]); k += counts[c]; }
        Arrays.sort(all);
        System.out.printf("requests=%d errors=%d throughput=%.0f req/s%n", total, errors.get(), total / (double) seconds);
        System.out.printf("latency ms: p50=%.1f p95=%.1f p99=%.1f max=%.1f%n",
                pct(all, 50), pct(all, 95), pct(all, 99), all[all.length - 1] / 1e6);
        System.out.println("reconciliation: " + HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/reconciliation")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static double pct(long[] sorted, int p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)] / 1e6;
    }

    private static HttpResponse<String> post(String url, String json, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (key != null) b.header("Idempotency-Key", key);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extractId(String json) {
        Matcher m = ID.matcher(json);
        if (!m.find()) throw new IllegalStateException("no id in " + json);
        return m.group(1);
    }
}
