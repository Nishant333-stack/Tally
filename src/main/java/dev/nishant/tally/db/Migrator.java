package dev.nishant.tally.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minimal Flyway-style migrator: applies db/migrations/V{n}__*.sql in version order,
 * each in its own transaction, and records applied versions in schema_version.
 */
public final class Migrator {
    private final ConnectionPool pool;
    private final Path dir;

    public Migrator(ConnectionPool pool, Path dir) {
        this.pool = pool;
        this.dir = dir;
    }

    public int migrate() throws SQLException, IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                     .sorted((a, b) -> Integer.compare(version(a), version(b)))
                     .toList();
        }
        int applied = 0;
        Connection c = pool.acquire();
        try {
            try (Statement st = c.createStatement()) {
                // serialize concurrent app instances racing to migrate
                st.execute("SELECT pg_advisory_xact_lock(7340211)");
                st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            }
            for (Path f : files) {
                int v = version(f);
                if (isApplied(c, v)) continue;
                try (Statement st = c.createStatement()) {
                    st.execute(Files.readString(f));
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
                    ps.setInt(1, v);
                    ps.executeUpdate();
                }
                applied++;
            }
            c.commit();
        } catch (SQLException | IOException e) {
            c.rollback();
            throw e;
        } finally {
            pool.release(c);
        }
        return applied;
    }

    private static boolean isApplied(Connection c, int v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM schema_version WHERE version = ?")) {
            ps.setInt(1, v);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static int version(Path p) {
        String n = p.getFileName().toString();
        return Integer.parseInt(n.substring(1, n.indexOf("__")));
    }
}
