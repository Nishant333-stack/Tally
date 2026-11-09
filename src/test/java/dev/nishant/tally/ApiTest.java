package dev.nishant.tally;

import dev.nishant.tally.TestKit.Res;

import java.util.List;
import java.util.Map;

import static dev.nishant.tally.TestKit.*;

public final class ApiTest {
    public static void testCreateAndFetchAccount() throws Exception {
        Res r = post("/api/accounts", Map.of("ownerName", "Asha", "email", "asha@example.com"));
        eq(201, r.status(), "status");
        eq(0L, r.get("balance"), "new wallets start at zero");
        eq("INR", r.get("currency"), "default currency");
        Res g = get("/api/accounts/" + r.get("id"));
        eq(200, g.status(), "fetch");
        eq("asha@example.com", g.get("email"), "email");
    }

    public static void testDuplicateEmailIsConflict() throws Exception {
        post("/api/accounts", Map.of("ownerName", "Dup", "email", "dup@example.com"));
        Res r = post("/api/accounts", Map.of("ownerName", "Dup2", "email", "DUP@example.com"));
        eq(409, r.status(), "case-insensitive duplicate email");
        eq("email_taken", errorCode(r), "code");
    }

    public static void testValidation() throws Exception {
        eq(400, post("/api/accounts", Map.of("ownerName", "", "email", "x@y.z")).status(), "blank name");
        eq(400, post("/api/accounts", Map.of("ownerName", "A", "email", "not-an-email")).status(), "bad email");
        eq(400, post("/api/accounts", "{bad json").status(), "malformed json");
        eq(400, get("/api/accounts/not-a-uuid").status(), "bad uuid");
        eq(404, get("/api/accounts/00000000-0000-0000-0000-00000000dead").status(), "unknown account");
        eq(404, get("/api/accounts/00000000-0000-0000-0000-000000000001").status(), "system account is hidden");
        eq(405, post("/api/health", Map.of()).status(), "wrong method");

        String a = newAccount("Val"), b = newAccount("Val2");
        fund(a, 1000);
        for (Object bad : List.of(0L, -5L, 10.5, "100")) {
            Res r = post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", bad), "val-" + bad);
            eq(400, r.status(), "invalid amount " + bad);
        }
        eq(400, post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", 1L)).status(), "missing idempotency key");
        eq(400, post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", a, "amount", 1L), "self").status(), "self transfer");
    }

    public static void testDepositTransferAndBalances() throws Exception {
        String a = newAccount("Ravi"), b = newAccount("Meera");
        fund(a, 50_000);
        Res t = post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", 12_345L, "note", "dinner"), "t-basic");
        eq(201, t.status(), "transfer created");
        eq("COMPLETED", t.get("status"), "status");
        eq(50_000 - 12_345, balance(a), "sender debited");
        eq(12_345, balance(b), "receiver credited");
        eq(200, get("/api/transfers/" + t.get("id")).status(), "fetch transfer");
        assertReconciled();
    }

    public static void testInsufficientFundsWritesNothing() throws Exception {
        String a = newAccount("Low"), b = newAccount("Other");
        fund(a, 100);
        Res r = post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", 101L), "t-nsf");
        eq(422, r.status(), "status");
        eq("insufficient_funds", errorCode(r), "code");
        eq(100, balance(a), "sender untouched");
        eq(0, balance(b), "receiver untouched");
        // the same key can be retried once funds arrive, because the failed attempt was rolled back
        fund(a, 1);
        eq(201, post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", 101L), "t-nsf").status(), "retry after top-up");
    }

    public static void testIdempotentReplay() throws Exception {
        String a = newAccount("Idem"), b = newAccount("Idem2");
        fund(a, 10_000);
        Map<String, Object> body = Map.of("fromAccountId", a, "toAccountId", b, "amount", 2_500L);
        Res first = post("/api/transfers", body, "idem-1");
        Res second = post("/api/transfers", body, "idem-1");
        eq(201, first.status(), "first");
        eq(200, second.status(), "replay");
        eq("true", second.raw().headers().firstValue("Idempotent-Replayed").orElse(null), "replay header");
        eq(first.get("id"), second.get("id"), "same transfer returned");
        eq(7_500, balance(a), "charged exactly once");

        Res misuse = post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", 9L), "idem-1");
        eq(422, misuse.status(), "same key, different body");
        eq("idempotency_key_reused", errorCode(misuse), "code");
    }

    public static void testPartialAndFullRefunds() throws Exception {
        String a = newAccount("Buyer"), b = newAccount("Seller");
        fund(a, 10_000);
        String tid = post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", 4_000L), "r-t").get("id");

        Res partial = post("/api/transfers/" + tid + "/refunds", Map.of("amount", 1_500L), "r-1");
        eq(201, partial.status(), "partial refund");
        eq("PARTIALLY_REFUNDED", get("/api/transfers/" + tid).get("status"), "status after partial");
        eq(200, post("/api/transfers/" + tid + "/refunds", Map.of("amount", 1_500L), "r-1").status(), "refund replay");

        Res over = post("/api/transfers/" + tid + "/refunds", Map.of("amount", 3_000L), "r-2");
        eq(422, over.status(), "over-refund");
        eq("refund_exceeds_remaining", errorCode(over), "code");

        eq(201, post("/api/transfers/" + tid + "/refunds", "", "r-3").status(), "refund remainder (empty body)");
        eq("REFUNDED", get("/api/transfers/" + tid).get("status"), "fully refunded");
        eq(10_000, balance(a), "buyer whole again");
        eq(0, balance(b), "seller back to zero");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sellerFeed = (List<Map<String, Object>>) get("/api/accounts/" + b + "/ledger").body().get("items");
        eq("REFUND", sellerFeed.get(0).get("kind"), "newest seller entry is the refund");
        eq("Buyer", sellerFeed.get(0).get("counterpartyName"), "refund counterparty is the original sender");
        eq(true, sellerFeed.get(sellerFeed.size() - 1).get("transferFullyRefunded"), "original credit marked refunded");
        eq("already_refunded", errorCode(post("/api/transfers/" + tid + "/refunds", "", "r-4")), "nothing left");
        assertReconciled();
    }

    @SuppressWarnings("unchecked")
    public static void testLedgerKeysetPagination() throws Exception {
        String a = newAccount("Pager"), b = newAccount("Pager2");
        fund(a, 1_000);
        for (int i = 1; i <= 5; i++)
            post("/api/transfers", Map.of("fromAccountId", a, "toAccountId", b, "amount", (long) i, "note", "n" + i), "pg-" + i);

        Res p1 = get("/api/accounts/" + a + "/ledger?limit=4");
        List<Map<String, Object>> items = (List<Map<String, Object>>) p1.body().get("items");
        eq(4, items.size(), "page size");
        eq(-5L, items.get(0).get("amount"), "newest first");
        eq("Pager2", items.get(0).get("counterpartyName"), "counterparty resolved");
        Object cursor = p1.body().get("nextCursor");
        check(cursor != null, "has next page");

        Res p2 = get("/api/accounts/" + a + "/ledger?limit=4&before=" + cursor);
        List<Map<String, Object>> rest = (List<Map<String, Object>>) p2.body().get("items");
        eq(2, rest.size(), "remaining entries (1 transfer + 1 deposit)");
        eq("DEPOSIT", rest.get(1).get("kind"), "oldest is the deposit");
        eq(null, p2.body().get("nextCursor"), "last page");
        eq(400, get("/api/accounts/" + a + "/ledger?limit=500").status(), "limit bounded");
    }
}
