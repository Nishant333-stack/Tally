package dev.nishant.tally.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs a unit of work in one database transaction. Transient conflicts
 * (serialization failure 40001, deadlock 40P01) are retried with jittered backoff;
 * everything else rolls back and propagates.
 */
public final class Tx {
    @FunctionalInterface
    public interface Work<T> { T run(Connection c) throws SQLException; }

    private static final int MAX_ATTEMPTS = 3;
    private final ConnectionPool pool;

    public Tx(ConnectionPool pool) { this.pool = pool; }

    public <T> T run(Work<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Connection c = pool.acquire();
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException e) {
                safeRollback(c);
                if (!isTransient(e)) throw e;
                last = e;
                backoff(attempt);
            } catch (RuntimeException e) {
                safeRollback(c);
                throw e;
            } finally {
                pool.release(c);
            }
        }
        throw last;
    }

    static boolean isTransient(SQLException e) {
        String s = e.getSQLState();
        return "40001".equals(s) || "40P01".equals(s);
    }

    private static void safeRollback(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep((long) (Math.random() * 10 * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
