package dev.nishant.tally.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-size JDBC connection pool. Callers borrow with {@link #acquire()} and must
 * {@link #release(Connection)} in a finally block (see {@link Tx}). Broken connections
 * are replaced on release so one bad socket never shrinks the pool.
 */
public final class ConnectionPool implements AutoCloseable {
    private final BlockingQueue<Connection> idle;
    private final String url, user, password;
    private final long acquireTimeoutMs;

    public ConnectionPool(String url, String user, String password, int size, long acquireTimeoutMs) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.idle = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) idle.add(open());
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        c.setAutoCommit(false);
        return c;
    }

    public Connection acquire() throws SQLException {
        try {
            Connection c = idle.poll(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (c == null) throw new SQLException("connection pool exhausted", "08001");
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for a connection", "08001", e);
        }
    }

    public void release(Connection c) {
        try {
            if (c.isClosed() || !c.isValid(1)) c = open();
            else if (!c.getAutoCommit()) c.rollback(); // never hand out a connection mid-transaction
        } catch (SQLException e) {
            try { c = open(); } catch (SQLException ignored) { return; }
        }
        idle.offer(c);
    }

    public int idleCount() { return idle.size(); }

    @Override
    public void close() {
        Connection c;
        while ((c = idle.poll()) != null) {
            try { c.close(); } catch (SQLException ignored) { }
        }
    }
}
