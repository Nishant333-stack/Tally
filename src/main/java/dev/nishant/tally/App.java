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
            String dbUrl = env("DB_URL", "jdbc:postgresql://127.0.0.1:5432/tally");
            String dbUser = env("DB_USER", "postgres");
            String dbPassword = env("DB_PASSWORD", "");
            // Hosted Postgres (Railway, Render, Heroku) hands out postgres://user:pass@host:port/db
            String platformUrl = System.getenv("DATABASE_URL");
            if (platformUrl != null && !platformUrl.isBlank()) {
                java.net.URI u = java.net.URI.create(platformUrl);
                String[] creds = u.getRawUserInfo().split(":", 2);
                dbUser = java.net.URLDecoder.decode(creds[0], java.nio.charset.StandardCharsets.UTF_8);
                dbPassword = creds.length > 1 ? java.net.URLDecoder.decode(creds[1], java.nio.charset.StandardCharsets.UTF_8) : "";
                dbUrl = "jdbc:postgresql://" + u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 5432) + u.getPath()
                        + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
            }
            return new Config(
                dbUrl,
                dbUser,
                dbPassword,
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
