package dev.nishant.tally;

import dev.nishant.tally.TestKit.Res;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.nishant.tally.TestKit.*;

public final class ConcurrencyTest {

    /** 50 clients fire the same request with the same key at once: exactly one transfer may happen. */
    public static void testConcurrentSameKeyChargesOnce() throws Exception {
        String a = newAccount("Race"), b = newAccount("Race2");
        fund(a, 1_000);
        Map<String, Object> body = Map.of("fromAccountId", a, "toAccountId", b, "amount", 100L);
        CountDownLatch go = new CountDownLatch(1);
        Map<Object, Boolean> ids = new ConcurrentHashMap<>();
        AtomicInteger created = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 50; i++) fs.add(pool.submit(() -> {
                go.await();
                Res r = post("/api/transfers", body, "same-key-race");
                check(r.status() == 201 || r.status() == 200, "unexpected status " + r.status() + " " + r.body());
                if (r.status() == 201) created.incrementAndGet();
                ids.put(r.get("id"), true);
                return null;
            }));
            go.countDown();
            for (Future<?> f : fs) f.get();
        }
        eq(1, created.get(), "exactly one 201");
        eq(1, ids.size(), "every response names the same transfer");
        eq(900, balance(a), "charged once");
    }

    /** A->B and B->A at the same time lock rows in UUID order, so they never deadlock. */
    public static void testOppositeTransfersDoNotDeadlock() throws Exception {
        String a = newAccount("Left"), b = newAccount("Right");
        fund(a, 100_000);
        fund(b, 100_000);
        AtomicInteger ok = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    boolean forward = n % 2 == 0;
                    Res r = post("/api/transfers", Map.of("fromAccountId", forward ? a : b, "toAccountId", forward ? b : a,
                            "amount", 10L), "dl-" + n);
                    if (r.status() == 201) ok.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        eq(400, ok.get(), "all opposite transfers succeed");
        eq(100_000, balance(a), "200 out, 200 in");
        eq(100_000, balance(b), "200 out, 200 in");
    }

    /** Random transfers among a small set of hot wallets; money is conserved and nobody goes negative. */
    public static void testRandomTransfersConserveMoney() throws Exception {
        int wallets = 8, transfers = 2_000;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < wallets; i++) {
            String id = newAccount("Hot" + i);
            fund(id, 5_000);
            ids.add(id);
        }
        AtomicInteger ok = new AtomicInteger(), nsf = new AtomicInteger(), other = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < transfers; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    int x = rnd.nextInt(wallets), y = (x + 1 + rnd.nextInt(wallets - 1)) % wallets;
                    Res r = post("/api/transfers", Map.of("fromAccountId", ids.get(x), "toAccountId", ids.get(y),
                            "amount", 1L + rnd.nextInt(1_500)), "rnd-" + n);
                    if (r.status() == 201) ok.incrementAndGet();
                    else if (r.status() == 422) nsf.incrementAndGet();
                    else other.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        eq(0, other.get(), "no 5xx / unexpected errors");
        eq(transfers, ok.get() + nsf.get(), "every request answered");
        long total = 0;
        for (String id : ids) {
            long bal = balance(id);
            check(bal >= 0, "negative balance " + bal);
            total += bal;
        }
        eq(wallets * 5_000L, total, "money conserved among hot wallets");
        assertReconciled();
        System.out.printf("    (%d committed, %d rejected for insufficient funds)%n", ok.get(), nsf.get());
    }
}
