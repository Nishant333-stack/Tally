package dev.nishant.tally.service;

import dev.nishant.tally.db.Tx;
import dev.nishant.tally.http.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Wallet business logic. Every money movement is one database transaction that:
 *  1. short-circuits on a known Idempotency-Key (returning the original result),
 *  2. locks both accounts with SELECT ... FOR UPDATE in UUID order (no deadlocks),
 *  3. validates funds and currency under the lock,
 *  4. writes the transfer row plus two ledger entries that sum to zero.
 */
public final class WalletService {
    public static final UUID FUNDING_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final long MAX_AMOUNT = 100_000_000_00L; // 10 crore in paise

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Result of an idempotent write: the resource and whether it was a replay of an earlier request. */
    public record Result(Map<String, Object> body, boolean replayed) { }

    private final Tx tx;

    public WalletService(Tx tx) { this.tx = tx; }

    // ------------------------------------------------------------------ accounts

    public Map<String, Object> createAccount(String ownerName, String email, String currency) throws SQLException {
        if (ownerName == null || ownerName.isBlank() || ownerName.length() > 100)
            throw ApiException.badRequest("ownerName is required (max 100 chars)");
        if (email == null || !EMAIL.matcher(email).matches() || email.length() > 254)
            throw ApiException.badRequest("a valid email is required");
        String cur = currency == null ? "INR" : currency;
        if (!CURRENCY.matcher(cur).matches()) throw ApiException.badRequest("currency must be a 3-letter ISO code");

        return tx.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (owner_name, email, currency) VALUES (?, ?, ?) " +
                    "ON CONFLICT (email) DO NOTHING " +
                    "RETURNING id, owner_name, email, currency, balance, created_at")) {
                ps.setString(1, ownerName.strip());
                ps.setString(2, email.toLowerCase());
                ps.setString(3, cur);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(409, "email_taken", "an account with this email already exists");
                    return account(rs);
                }
            }
        });
    }

    public Map<String, Object> getAccount(String id) throws SQLException {
        UUID uuid = uuid(id, "account id");
        return tx.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, owner_name, email, currency, balance, created_at FROM accounts WHERE id = ? AND kind = 'USER'")) {
                ps.setObject(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ApiException.notFound("account");
                    return account(rs);
                }
            }
        });
    }

    public List<Map<String, Object>> listAccounts() throws SQLException {
        return tx.run(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, owner_name, email, currency, balance, created_at FROM accounts " +
                    "WHERE kind = 'USER' ORDER BY created_at LIMIT 100");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(account(rs));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ money movement

    public Result deposit(String accountId, Object amount, String idemKey) throws SQLException {
        return move("DEPOSIT", FUNDING_ACCOUNT, uuid(accountId, "account id"), amount(amount), null, idemKey);
    }

    public Result transfer(String from, String to, Object amount, String note, String idemKey) throws SQLException {
        UUID f = uuid(from, "fromAccountId"), t = uuid(to, "toAccountId");
        if (f.equals(t)) throw ApiException.badRequest("cannot send money to the same account");
        if (note != null && note.length() > 140) throw ApiException.badRequest("note max 140 chars");
        return move("P2P", f, t, amount(amount), note, idemKey);
    }

    private Result move(String type, UUID from, UUID to, long amount, String note, String idemKey) throws SQLException {
        requireKey(idemKey);
        String hash = hash(type, from, to, amount, note);

        return tx.run(c -> {
            Map<String, Object> prior = findTransferByKey(c, idemKey);
            if (prior != null) return replay(prior, hash);

            Map<UUID, Locked> acc = lockAccounts(c, from, to);
            Locked src = acc.get(from), dst = acc.get(to);
            if (src == null) throw ApiException.notFound("source account");
            if (dst == null || dst.kind.equals("SYSTEM")) throw ApiException.notFound("destination account");
            if (type.equals("P2P") && src.kind.equals("SYSTEM")) throw ApiException.notFound("source account");
            if (!src.currency.equals(dst.currency))
                throw new ApiException(422, "currency_mismatch", "accounts use different currencies");
            if (src.kind.equals("USER") && src.balance < amount)
                throw new ApiException(422, "insufficient_funds", "balance is lower than the transfer amount");

            UUID transferId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transfers (idempotency_key, request_hash, type, from_account, to_account, amount, currency, note) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")) {
                ps.setString(1, idemKey);
                ps.setString(2, hash);
                ps.setString(3, type);
                ps.setObject(4, from);
                ps.setObject(5, to);
                ps.setLong(6, amount);
                ps.setString(7, src.currency);
                ps.setString(8, note);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return replay(findTransferByKey(c, idemKey), hash); // lost a same-key race
                    transferId = (UUID) rs.getObject(1);
                }
            }
            post(c, from, -amount, transferId, null);
            post(c, to, amount, transferId, null);
            return new Result(findTransfer(c, transferId), false);
        });
    }

    public Result refund(String transferId, Object amountOrNull, String idemKey) throws SQLException {
        requireKey(idemKey);
        UUID tid = uuid(transferId, "transfer id");
        Long requested = amountOrNull == null ? null : amount(amountOrNull);

        return tx.run(c -> {
            Map<String, Object> prior = findRefundByKey(c, idemKey);
            String hash = hash("REFUND", tid, null, requested == null ? -1 : requested, null);
            if (prior != null) return replay(prior, hash);

            UUID from, to;
            long original, refunded;
            String type;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT from_account, to_account, amount, refunded_amount, type FROM transfers WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, tid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ApiException.notFound("transfer");
                    from = (UUID) rs.getObject(1);
                    to = (UUID) rs.getObject(2);
                    original = rs.getLong(3);
                    refunded = rs.getLong(4);
                    type = rs.getString(5);
                }
            }
            if (!type.equals("P2P")) throw new ApiException(422, "not_refundable", "only P2P transfers can be refunded");
            long remaining = original - refunded;
            long amount = requested == null ? remaining : requested;
            if (remaining == 0) throw new ApiException(422, "already_refunded", "transfer is fully refunded");
            if (amount > remaining)
                throw new ApiException(422, "refund_exceeds_remaining", "at most " + remaining + " can still be refunded");

            Map<UUID, Locked> acc = lockAccounts(c, from, to);
            if (acc.get(to).balance < amount)
                throw new ApiException(422, "insufficient_funds", "recipient no longer holds enough to refund");

            UUID refundId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO refunds (idempotency_key, request_hash, transfer_id, amount) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")) {
                ps.setString(1, idemKey);
                ps.setString(2, hash);
                ps.setObject(3, tid);
                ps.setLong(4, amount);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return replay(findRefundByKey(c, idemKey), hash);
                    refundId = (UUID) rs.getObject(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE transfers SET refunded_amount = refunded_amount + ? WHERE id = ?")) {
                ps.setLong(1, amount);
                ps.setObject(2, tid);
                ps.executeUpdate();
            }
            post(c, to, -amount, tid, refundId);
            post(c, from, amount, tid, refundId);
            return new Result(findRefund(c, refundId), false);
        });
    }

    // ------------------------------------------------------------------ reads

    public Map<String, Object> getTransfer(String id) throws SQLException {
        UUID uuid = uuid(id, "transfer id");
        return tx.run(c -> {
            Map<String, Object> t = findTransfer(c, uuid);
            if (t == null) throw ApiException.notFound("transfer");
            return t;
        });
    }

    /** Newest-first activity feed with keyset pagination (stable under concurrent inserts, O(limit) per page). */
    public Map<String, Object> ledger(String accountId, String limitParam, String beforeParam) throws SQLException {
        UUID acct = uuid(accountId, "account id");
        int limit = parseInt(limitParam, 20, 1, 100, "limit");
        long before = beforeParam == null ? Long.MAX_VALUE : parseLong(beforeParam, "before");
        getAccount(accountId); // 404 for unknown accounts

        return tx.run(c -> {
            List<Map<String, Object>> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.id, e.amount, e.balance_after, e.created_at, e.transfer_id, e.refund_id, t.type, t.note, " +
                    "       (t.refunded_amount = t.amount) AS fully_refunded, " +
                    "       CASE WHEN e.account_id = t.from_account THEN t.to_account ELSE t.from_account END AS counterparty, " +
                    "       cp.owner_name AS counterparty_name " +
                    "FROM ledger_entries e " +
                    "JOIN transfers t ON t.id = e.transfer_id " +
                    "JOIN accounts cp ON cp.id = CASE WHEN e.account_id = t.from_account THEN t.to_account ELSE t.from_account END " +
                    "WHERE e.account_id = ? AND e.id < ? ORDER BY e.id DESC LIMIT ?")) {
                ps.setObject(1, acct);
                ps.setLong(2, before);
                ps.setInt(3, limit + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("entryId", rs.getLong("id"));
                        m.put("amount", rs.getLong("amount"));
                        m.put("balanceAfter", rs.getLong("balance_after"));
                        m.put("kind", rs.getObject("refund_id") != null ? "REFUND" : rs.getString("type"));
                        m.put("transferId", rs.getObject("transfer_id").toString());
                        m.put("counterpartyId", rs.getObject("counterparty").toString());
                        m.put("counterpartyName", rs.getString("counterparty_name"));
                        m.put("note", rs.getString("note"));
                        m.put("transferFullyRefunded", rs.getBoolean("fully_refunded"));
                        m.put("createdAt", rs.getObject("created_at").toString());
                        items.add(m);
                    }
                }
            }
            boolean more = items.size() > limit;
            if (more) items = items.subList(0, limit);
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", items);
            page.put("nextCursor", more ? items.get(items.size() - 1).get("entryId") : null);
            return page;
        });
    }

    /** Reconciliation: money is conserved and every balance equals the sum of its ledger entries. */
    public Map<String, Object> checkInvariants() throws SQLException {
        return tx.run(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT (SELECT COALESCE(SUM(balance),0) FROM accounts) AS total, " +
                    "(SELECT COUNT(*) FROM accounts a WHERE a.balance <> " +
                    "   COALESCE((SELECT SUM(amount) FROM ledger_entries e WHERE e.account_id = a.id),0)) AS drifted, " +
                    "(SELECT COUNT(*) FROM (SELECT transfer_id, refund_id FROM ledger_entries " +
                    "   GROUP BY transfer_id, refund_id HAVING SUM(amount) <> 0 OR COUNT(*) <> 2) x) AS unbalanced, " +
                    "(SELECT COUNT(*) FROM accounts WHERE kind = 'USER' AND balance < 0) AS negative, " +
                    "(SELECT COUNT(*) FROM ledger_entries) AS entries");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                m.put("totalBalance", rs.getLong("total"));
                m.put("driftedAccounts", rs.getLong("drifted"));
                m.put("unbalancedMovements", rs.getLong("unbalanced"));
                m.put("negativeUserBalances", rs.getLong("negative"));
                m.put("ledgerEntries", rs.getLong("entries"));
                m.put("ok", rs.getLong("total") == 0 && rs.getLong("drifted") == 0
                        && rs.getLong("unbalanced") == 0 && rs.getLong("negative") == 0);
            }
            return m;
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Locked(String kind, String currency, long balance) { }

    /** Lock rows in a global order (by UUID) so two opposite transfers can never deadlock. */
    private static Map<UUID, Locked> lockAccounts(Connection c, UUID a, UUID b) throws SQLException {
        Map<UUID, Locked> out = new java.util.HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, kind, currency, balance FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
            ps.setObject(1, a);
            ps.setObject(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put((UUID) rs.getObject(1), new Locked(rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        }
        return out;
    }

    private static void post(Connection c, UUID account, long delta, UUID transferId, UUID refundId) throws SQLException {
        long after;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET balance = balance + ? WHERE id = ? RETURNING balance")) {
            ps.setLong(1, delta);
            ps.setObject(2, account);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                after = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entries (account_id, transfer_id, refund_id, amount, balance_after) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, account);
            ps.setObject(2, transferId);
            ps.setObject(3, refundId);
            ps.setLong(4, delta);
            ps.setLong(5, after);
            ps.executeUpdate();
        }
    }

    private static Result replay(Map<String, Object> prior, String hash) {
        if (!hash.equals(prior.remove("_hash")))
            throw new ApiException(422, "idempotency_key_reused", "Idempotency-Key was already used with different parameters");
        return new Result(prior, true);
    }

    private static Map<String, Object> findTransferByKey(Connection c, String key) throws SQLException {
        return queryTransfer(c, "t.idempotency_key = ?", key);
    }

    private static Map<String, Object> findTransfer(Connection c, UUID id) throws SQLException {
        Map<String, Object> t = queryTransfer(c, "t.id = ?", id);
        if (t != null) t.remove("_hash");
        return t;
    }

    private static Map<String, Object> queryTransfer(Connection c, String where, Object arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, type, from_account, to_account, amount, currency, note, refunded_amount, created_at, request_hash " +
                "FROM transfers t WHERE " + where)) {
            ps.setObject(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id").toString());
                m.put("type", rs.getString("type"));
                m.put("fromAccountId", rs.getObject("from_account").toString());
                m.put("toAccountId", rs.getObject("to_account").toString());
                m.put("amount", rs.getLong("amount"));
                m.put("currency", rs.getString("currency"));
                m.put("note", rs.getString("note"));
                long refunded = rs.getLong("refunded_amount");
                m.put("refundedAmount", refunded);
                m.put("status", refunded == 0 ? "COMPLETED" : refunded == rs.getLong("amount") ? "REFUNDED" : "PARTIALLY_REFUNDED");
                m.put("createdAt", rs.getObject("created_at").toString());
                m.put("_hash", rs.getString("request_hash"));
                return m;
            }
        }
    }

    private static Map<String, Object> findRefundByKey(Connection c, String key) throws SQLException {
        return queryRefund(c, "idempotency_key = ?", key);
    }

    private static Map<String, Object> findRefund(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = queryRefund(c, "id = ?", id);
        r.remove("_hash");
        return r;
    }

    private static Map<String, Object> queryRefund(Connection c, String where, Object arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, transfer_id, amount, created_at, request_hash FROM refunds WHERE " + where)) {
            ps.setObject(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id").toString());
                m.put("transferId", rs.getObject("transfer_id").toString());
                m.put("amount", rs.getLong("amount"));
                m.put("createdAt", rs.getObject("created_at").toString());
                m.put("_hash", rs.getString("request_hash"));
                return m;
            }
        }
    }

    private static Map<String, Object> account(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id").toString());
        m.put("ownerName", rs.getString("owner_name"));
        m.put("email", rs.getString("email"));
        m.put("currency", rs.getString("currency"));
        m.put("balance", rs.getLong("balance"));
        m.put("createdAt", rs.getObject("created_at").toString());
        return m;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255)
            throw ApiException.badRequest("Idempotency-Key header is required (1-255 chars)");
    }

    static long amount(Object v) {
        if (!(v instanceof Long l)) throw ApiException.badRequest("amount must be an integer in minor units (e.g. paise)");
        if (l <= 0 || l > MAX_AMOUNT) throw ApiException.badRequest("amount must be between 1 and " + MAX_AMOUNT);
        return l;
    }

    static UUID uuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw ApiException.badRequest(field + " must be a UUID");
        }
    }

    private static int parseInt(String s, int dflt, int min, int max, String field) {
        if (s == null) return dflt;
        try {
            int v = Integer.parseInt(s);
            if (v < min || v > max) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        }
    }

    private static long parseLong(String s, String field) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be an integer cursor");
        }
    }

    private static String hash(String type, UUID from, UUID to, long amount, String note) {
        try {
            String canonical = type + '|' + from + '|' + to + '|' + amount + '|' + (note == null ? "" : note);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
