package dev.nishant.tally.api;

import dev.nishant.tally.http.Router;
import dev.nishant.tally.http.Router.Response;
import dev.nishant.tally.service.WalletService;

import java.util.Map;

/** REST surface. All writes require an Idempotency-Key header; replays return 200 with Idempotent-Replayed: true. */
public final class Routes {
    private Routes() { }

    public static Router register(Router r, WalletService wallet) {
        return r
            .on("GET", "/api/health", req -> Response.ok(Map.of("status", "ok")))

            .on("POST", "/api/accounts", req -> {
                Map<String, Object> b = req.json();
                return Response.created(wallet.createAccount(str(b, "ownerName"), str(b, "email"), str(b, "currency")));
            })
            .on("GET", "/api/accounts", req -> Response.ok(Map.of("items", wallet.listAccounts())))
            .on("GET", "/api/accounts/{id}", req -> Response.ok(wallet.getAccount(req.params().get("id"))))
            .on("GET", "/api/accounts/{id}/ledger", req -> Response.ok(
                    wallet.ledger(req.params().get("id"), req.query().get("limit"), req.query().get("before"))))

            .on("POST", "/api/accounts/{id}/deposits", req -> written(req,
                    wallet.deposit(req.params().get("id"), req.json().get("amount"), req.header("Idempotency-Key"))))

            .on("POST", "/api/transfers", req -> {
                Map<String, Object> b = req.json();
                return written(req, wallet.transfer(str(b, "fromAccountId"), str(b, "toAccountId"),
                        b.get("amount"), str(b, "note"), req.header("Idempotency-Key")));
            })
            .on("GET", "/api/transfers/{id}", req -> Response.ok(wallet.getTransfer(req.params().get("id"))))
            .on("POST", "/api/transfers/{id}/refunds", req -> {
                Object amount = req.body() == null || req.body().isBlank() ? null : req.json().get("amount");
                return written(req, wallet.refund(req.params().get("id"), amount, req.header("Idempotency-Key")));
            })

            .on("GET", "/api/admin/reconciliation", req -> Response.ok(wallet.checkInvariants()));
    }

    private static Response written(Router.Request req, WalletService.Result result) {
        if (result.replayed()) {
            req.exchange().getResponseHeaders().set("Idempotent-Replayed", "true");
            return Response.ok(result.body());
        }
        return Response.created(result.body());
    }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) throw dev.nishant.tally.http.ApiException.badRequest(key + " must be a string");
        return s;
    }
}
