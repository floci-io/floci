package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each cluster's database on one PostgreSQL server that already runs, with no Docker. A cluster has
 * a database of its own, {@code dsql_<id>}, owned by the NOLOGIN role {@code dsqlowner_<id>}, and
 * its backend roles carry the prefix {@code d<id>_}, starting with its admin. Floci connects as the
 * configured user, which must be able to create databases and roles, and gives each backend role a
 * password derived from the cluster's secret, as the server is not assumed to trust Floci. The
 * server must run PostgreSQL 16 or later, whose CREATEROLE keeps each admin to its own roles.
 */
@ApplicationScoped
public class ExternalDsqlBackend implements DsqlBackend {

    private static final Logger LOG = Logger.getLogger(ExternalDsqlBackend.class);
    private static final String HMAC = "HmacSHA256";
    private static final int SECRET_BYTES = 32;
    /** Before 16, CREATEROLE lets a cluster's admin alter and grant every other cluster's roles. */
    static final int MIN_SERVER_VERSION = 16;
    private static final int SCRAM_ITERATIONS = 4096;
    private static final int SCRAM_SALT_BYTES = 16;

    private final EmulatorConfig config;

    @Inject
    public ExternalDsqlBackend(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Creates the cluster's owner, admin and database where missing, then installs DSQL's dialect
     * in the database. The secret is set on {@code cluster} the first time, for the caller to save
     * with it. On failure, what this attempt created is dropped again, and nothing else. A server
     * older than PostgreSQL 16 is refused before anything is created.
     */
    @Override
    public DsqlDatabases.Backend start(DsqlCluster cluster) throws SQLException {
        if (cluster.getBackendSecret() == null) {
            cluster.setBackendSecret(newSecret());
        }
        String secret = cluster.getBackendSecret();
        Names names = new Names(cluster.getIdentifier());
        boolean createdOwner = false;
        boolean createdAdmin = false;
        boolean createdDatabase = false;
        try (Connection server = connect(external().database())) {
            requireSupportedVersion(server, host() + ":" + external().port());
            try {
                if (!roleExists(server, names.owner())) {
                    execute(server, "CREATE ROLE " + quote(names.owner()) + " NOLOGIN");
                    createdOwner = true;
                }
                String password = literal(verifier(secret, names.admin()));
                if (roleExists(server, names.admin())) {
                    execute(server, "ALTER ROLE " + quote(names.admin()) + " PASSWORD " + password);
                } else {
                    execute(server, "CREATE ROLE " + quote(names.admin()) + " LOGIN CREATEROLE PASSWORD " + password);
                    createdAdmin = true;
                }
                if (!databaseExists(server, names.database())) {
                    execute(server, "CREATE DATABASE " + quote(names.database()) + " OWNER " + quote(names.owner()));
                    createdDatabase = true;
                }
                execute(server, "REVOKE CONNECT ON DATABASE " + quote(names.database()) + " FROM PUBLIC");
                execute(server, "GRANT CONNECT ON DATABASE " + quote(names.database()) + " TO " + quote(names.admin()));
                try (Connection database = connect(names.database());
                     Statement statement = database.createStatement()) {
                    statement.execute(DsqlBootstrap.render(names.database(), names.owner(), names.admin(),
                            names.prefix(), true));
                }
            } catch (SQLException | RuntimeException e) {
                undo(server, names, createdOwner, createdAdmin, createdDatabase, e);
                throw e;
            }
        }
        LOG.infov("DSQL cluster {0} serves from database {1} on {2}:{3}", cluster.getIdentifier(),
                names.database(), host(), String.valueOf(external().port()));
        return new DsqlDatabases.Backend(host(), external().port(), names.database(), external().username(),
                external().password().orElse(null), new PrefixedRoleNames(cluster.getIdentifier()),
                new DerivedCredentials(secret));
    }

    /**
     * The cluster's recorded roles brought up to date, its own roles first, its admin, and its
     * owner last. With the database gone, the roles an earlier deletion saved, or else the admin
     * and owner, the two roles a cluster always has.
     */
    @Override
    public List<String> rolesToDrop(DsqlCluster cluster) throws SQLException {
        Names names = new Names(cluster.getIdentifier());
        try (Connection server = connect(external().database())) {
            if (!databaseExists(server, names.database())) {
                return known(cluster, names);
            }
        }
        List<String> roles = new ArrayList<>();
        try (Connection database = connect(names.database())) {
            if (!recordsRoles(database)) {
                return known(cluster, names);
            }
            execute(database, "CALL sys._record_backend_roles()");
            try (PreparedStatement query = database.prepareStatement("SELECT backend_name FROM sys.backend_roles"
                    + " ORDER BY CASE backend_name WHEN ? THEN 2 WHEN ? THEN 1 ELSE 0 END, role_oid DESC")) {
                query.setString(1, names.owner());
                query.setString(2, names.admin());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        roles.add(rows.getString(1));
                    }
                }
            }
        }
        // The admin and owner go with the cluster even when the record no longer names them.
        if (!roles.contains(names.admin())) {
            roles.add(roles.contains(names.owner()) ? roles.indexOf(names.owner()) : roles.size(), names.admin());
        }
        if (!roles.contains(names.owner())) {
            roles.add(names.owner());
        }
        return List.copyOf(roles);
    }

    /**
     * With {@code dropData}, ends every session on the cluster's database, drops the database, then
     * drops each role in {@link DsqlCluster#getPendingBackendRoles()}. What is already gone is
     * passed over; anything else that fails is thrown, so the deletion is not taken for done.
     * Without it nothing happens: the server keeps running for the next start.
     */
    @Override
    public void stop(DsqlCluster cluster, boolean dropData) {
        if (!dropData) {
            return;
        }
        Names names = new Names(cluster.getIdentifier());
        List<String> roles = cluster.getPendingBackendRoles() == null || cluster.getPendingBackendRoles().isEmpty()
                ? List.of(names.admin(), names.owner()) : cluster.getPendingBackendRoles();
        try (Connection server = connect(external().database())) {
            dropDatabase(server, names);
            for (String role : roles) {
                if (!names.owns(role)) {
                    LOG.warnv("Role {0} is not one of DSQL cluster {1}'s roles; it is left in place", role,
                            cluster.getIdentifier());
                    continue;
                }
                execute(server, "DROP ROLE IF EXISTS " + quote(role));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The database and roles of DSQL cluster " + cluster.getIdentifier()
                    + " could not be dropped: " + e.getMessage(), e);
        }
    }

    /**
     * A FAILED cluster's server may be one Floci cannot reach, so its deletion finishes anyway, as
     * on AWS; its database and recorded roles may stay on the server.
     */
    @Override
    public Optional<String> leftoversOfFailed(DsqlCluster cluster) {
        Names names = new Names(cluster.getIdentifier());
        List<String> pending = cluster.getPendingBackendRoles();
        List<String> roles = pending == null || pending.isEmpty() ? List.of(names.admin(), names.owner()) : pending;
        return Optional.of("database " + names.database() + " and roles " + String.join(", ", roles));
    }

    /** The password of a backend role: base64url, unpadded, of HMAC-SHA256 keyed by the cluster's secret. */
    static String derive(String secret, String backendRole) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), HMAC));
            byte[] digest = mac.doFinal(backendRole.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /**
     * The SCRAM-SHA-256 verifier of a backend role's derived password, in PostgreSQL's format,
     * {@code SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>}, which {@code PASSWORD}
     * stores as it is. The salt, the first 16 bytes of HMAC-SHA256 of {@code scram-salt:<role>}
     * keyed by the cluster's secret, is the same each time, so setting it again changes nothing.
     */
    static String verifier(String secret, String backendRole) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), HMAC));
            byte[] salt = Arrays.copyOf(mac.doFinal(("scram-salt:" + backendRole).getBytes(StandardCharsets.UTF_8)),
                    SCRAM_SALT_BYTES);
            byte[] salted = BackendAuthenticator.saltedPassword(derive(secret, backendRole), salt, SCRAM_ITERATIONS);
            byte[] clientKey = BackendAuthenticator.hmac(salted, "Client Key".getBytes(StandardCharsets.UTF_8));
            byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
            byte[] serverKey = BackendAuthenticator.hmac(salted, "Server Key".getBytes(StandardCharsets.UTF_8));
            Base64.Encoder base64 = Base64.getEncoder();
            return "SCRAM-SHA-256$" + SCRAM_ITERATIONS + ":" + base64.encodeToString(salt) + "$"
                    + base64.encodeToString(storedKey) + ":" + base64.encodeToString(serverKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SCRAM-SHA-256 is not available", e);
        }
    }

    /**
     * Each backend role's password and its verifier, derived from the cluster's secret. A verifier
     * is computed once per role, as its PBKDF2 would otherwise run on every login.
     */
    private static final class DerivedCredentials implements BackendCredentials {
        private final String secret;
        private final Map<String, String> verifiers = new ConcurrentHashMap<>();

        DerivedCredentials(String secret) {
            this.secret = secret;
        }

        @Override
        public String passwordFor(String backendRole) {
            return derive(secret, backendRole);
        }

        @Override
        public String verifierFor(String backendRole) {
            return verifiers.computeIfAbsent(backendRole, role -> verifier(secret, role));
        }

        @Override
        public String toString() {
            return "DerivedCredentials[secret=***]";
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        // Not a static field, which a native image would seed at build time.
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static List<String> known(DsqlCluster cluster, Names names) {
        List<String> pending = cluster.getPendingBackendRoles();
        if (pending != null && !pending.isEmpty()) {
            return List.copyOf(pending);
        }
        LOG.warnv("The database of DSQL cluster {0} is gone: only its admin and owner roles are known to drop;"
                + " roles created in it are left on the server", cluster.getIdentifier());
        return List.of(names.admin(), names.owner());
    }

    private void undo(Connection server, Names names, boolean createdOwner, boolean createdAdmin,
                      boolean createdDatabase, Exception failure) {
        try {
            if (createdDatabase) {
                dropDatabase(server, names);
            } else if (createdAdmin && databaseExists(server, names.database())) {
                // A database this attempt did not create keeps the admin's CONNECT, which would keep the
                // admin from being dropped.
                execute(server, "REVOKE CONNECT ON DATABASE " + quote(names.database()) + " FROM "
                        + quote(names.admin()));
            }
            if (createdAdmin) {
                execute(server, "DROP ROLE IF EXISTS " + quote(names.admin()));
            }
            if (createdOwner) {
                execute(server, "DROP ROLE IF EXISTS " + quote(names.owner()));
            }
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private static void dropDatabase(Connection server, Names names) throws SQLException {
        try (PreparedStatement terminate = server.prepareStatement("SELECT pg_terminate_backend(pid)"
                + " FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()")) {
            terminate.setString(1, names.database());
            terminate.execute();
        }
        execute(server, "DROP DATABASE IF EXISTS " + quote(names.database()) + " WITH (FORCE)");
    }

    /**
     * Refuses a server older than PostgreSQL 16. Isolating the clusters from one another rests on
     * its CREATEROLE, which only lets a role alter and grant the roles it created itself.
     */
    static void requireSupportedVersion(Connection server, String where) throws SQLException {
        DatabaseMetaData metaData = server.getMetaData();
        if (metaData.getDatabaseMajorVersion() < MIN_SERVER_VERSION) {
            throw new SQLException("Floci's external DSQL backend needs PostgreSQL " + MIN_SERVER_VERSION
                    + " or later; " + where + " runs " + metaData.getDatabaseProductVersion());
        }
    }

    private static boolean roleExists(Connection server, String role) throws SQLException {
        return exists(server, "SELECT 1 FROM pg_roles WHERE rolname = ?", role);
    }

    private static boolean databaseExists(Connection server, String database) throws SQLException {
        return exists(server, "SELECT 1 FROM pg_database WHERE datname = ?", database);
    }

    /** Whether the bootstrap got as far as recording the cluster's roles. */
    private static boolean recordsRoles(Connection database) throws SQLException {
        try (Statement statement = database.createStatement();
             ResultSet row = statement.executeQuery("SELECT to_regprocedure('sys._record_backend_roles()') IS NOT NULL"
                     + " AND to_regclass('sys.backend_roles') IS NOT NULL")) {
            return row.next() && row.getBoolean(1);
        }
    }

    private static boolean exists(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connect(String database) throws SQLException {
        String url = "jdbc:postgresql://" + host() + ":" + external().port() + "/" + database;
        return DriverManager.getConnection(url, external().username(), external().password().orElse(""));
    }

    private String host() {
        return external().host().orElseThrow(() -> new IllegalStateException(
                "floci.services.dsql.external.host is required with the external DSQL backend"));
    }

    private EmulatorConfig.DsqlServiceConfig.External external() {
        return config.services().dsql().external();
    }

    /** An identifier quoted as quote_ident quotes one that needs it; always quoting is equivalent. */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** The names of one cluster's database and roles on the shared server. */
    private record Names(String database, String owner, String admin, String prefix) {
        Names(String clusterId) {
            this("dsql_" + clusterId, "dsqlowner_" + clusterId, "d" + clusterId + "_admin", "d" + clusterId + "_");
        }

        boolean owns(String role) {
            return role.equals(owner) || role.startsWith(prefix);
        }
    }
}
