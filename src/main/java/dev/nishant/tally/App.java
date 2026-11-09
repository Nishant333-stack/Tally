package dev.nishant.tally;

import com.sun.net.httpserver.HttpServer;
import dev.nishant.tally.api.Routes;
import dev.nishant.tally.db.ConnectionPool;
import dev.nishant.tally.db.Migrator;
import dev.nishant.tally.db.Tx;
import dev.nishant.tally.http.Router;
import dev.nishant.tally.service.WalletService;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/** Boots the pool, runs migrations, and serves the REST API + React app on one port. */
public final class App implements AutoCloseable {
    static {
        // store and render timestamps in UTC regardless of host locale (also avoids
        // legacy zone aliases such as Asia/Calcutta that some Postgres builds reject)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    private final ConnectionPool pool;
    private final HttpServer server;

    public App(Config cfg) throws Exception {
        pool = new ConnectionPool(cfg.dbUrl, cfg.dbUser, cfg.dbPassword, cfg.poolSize, 2_000);
        new Migrator(pool, cfg.migrations).migrate();
        WalletService wallet = new WalletService(new Tx(pool));
        Router router = Routes.register(new Router(cfg.staticDir), wallet);

        server = HttpServer.create(new InetSocketAddress(cfg.port), 1024);
        server.createContext("/", router);
        // one cheap virtual thread per request; the DB pool is the real concurrency limit
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public App start() { server.start(); return this; }
    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() {
        server.stop(0);
        pool.close();
    }

    public record Config(String dbUrl, String dbUser, String dbPassword, int poolSize, int port,
                         Path migrations, Path staticDir) {
        public static Config fromEnv() {
            return new Config(
                env("DB_URL", "jdbc:postgresql://127.0.0.1:5432/tally"),
                env("DB_USER", "postgres"),
                env("DB_PASSWORD", ""),
                Integer.parseInt(env("DB_POOL_SIZE", "20")),
                Integer.parseInt(env("PORT", "8080")),
                Path.of(env("MIGRATIONS_DIR", "db/migrations")),
                Path.of(env("STATIC_DIR", "web/dist")));
        }

        private static String env(String k, String d) {
            String v = System.getenv(k);
            return v == null || v.isBlank() ? d : v;
        }
    }

    public static void main(String[] args) throws Exception {
        App app = new App(Config.fromEnv()).start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println("Tally listening on http://localhost:" + app.port());
    }
}
