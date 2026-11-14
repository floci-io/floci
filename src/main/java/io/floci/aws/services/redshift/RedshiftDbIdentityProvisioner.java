package io.floci.aws.services.redshift;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provisions or verifies the PostgreSQL role and temporary {@code DbGroups} memberships
 * corresponding to an IAM-derived Redshift credential, and gives the session a password to log
 * in as that role. Logging in as the role, rather than switching to it from the master, leaves
 * the blocking of a switch to another user to PostgreSQL, which authorizes
 * {@code SET SESSION AUTHORIZATION} against the authenticated user, and loads the role's own
 * {@code ALTER USER ... SET} defaults as a direct login does.
 */
@ApplicationScoped
public class RedshiftDbIdentityProvisioner {

    private static final Logger LOG = Logger.getLogger(RedshiftDbIdentityProvisioner.class);

    /**
     * {@code backendPassword} is the password to log in as {@code databaseRole}, or {@code null}
     * when the role is the connection's own master user, which logs in with the master password.
     * {@code restorePassword} puts the role's own password back and is called once the backend login
     * has completed; {@code releaseLease} ends the whole identity (it restores the password too, if
     * that has not happened yet) and is called when the session closes.
     */
    public record PreparedIdentity(String databaseRole, String backendPassword,
                                   Runnable restorePassword, Runnable releaseLease) { }

    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection open() throws SQLException;
    }

    private final TempGroupGrantTracker grantTracker;
    private final TempRolePasswordTracker passwordTracker;

    @Inject
    public RedshiftDbIdentityProvisioner(TempGroupGrantTracker grantTracker,
                                         TempRolePasswordTracker passwordTracker) {
        this.grantTracker = grantTracker;
        this.passwordTracker = passwordTracker;
    }

    /**
     * Ensures {@code roleName} exists (creating it when {@code autoCreate} is true), validates
     * every group in {@code dbGroups}, grants temporary membership for groups the role does
     * not already belong to, and installs the temporary login password. Must be called on a
     * master/admin connection.
     */
    public PreparedIdentity prepare(Connection adminConnection, String backendKey,
                                    String roleName, boolean autoCreate, List<String> dbGroups,
                                    ConnectionSupplier adminConnectionFactory) throws SQLException {
        if (roleName == null || roleName.isBlank()) {
            throw new SQLException("Database role name must not be blank", "28000");
        }
        grantPublicSchemaAccess(adminConnection);
        RoleInfo role = ensureLoginRole(adminConnection, roleName, autoCreate);

        List<String> groupsToGrant = resolveTemporaryGroups(adminConnection, dbGroups);
        TempGroupGrantTracker.Lease groupLease = grantTracker.acquire(
                backendKey,
                roleName,
                groupsToGrant,
                group -> executeSql(adminConnection,
                        "GRANT " + quoteIdentifier(group) + " TO " + quoteIdentifier(roleName)),
                group -> executeSql(adminConnection,
                        "REVOKE " + quoteIdentifier(group) + " FROM " + quoteIdentifier(roleName)),
                group -> isDirectMember(adminConnection, roleName, group));

        TempRolePasswordTracker.Lease passwordLease = null;
        if (!role.currentUser()) {
            try {
                passwordLease = passwordTracker.acquire(
                        backendKey,
                        roleName,
                        () -> readPasswordHash(adminConnection, roleName),
                        password -> setPassword(adminConnection, roleName, password));
            } catch (SQLException | RuntimeException failure) {
                releaseGroups(groupLease, roleName, adminConnectionFactory);
                throw failure;
            }
        }

        TempRolePasswordTracker.Lease heldPassword = passwordLease;
        Runnable releaseHandle = () -> {
            releaseGroups(groupLease, roleName, adminConnectionFactory);
            releasePassword(heldPassword, roleName, adminConnectionFactory);
        };
        Runnable restorePassword = () -> releasePassword(heldPassword, roleName, adminConnectionFactory);
        return new PreparedIdentity(roleName, heldPassword == null ? null : heldPassword.password(),
                restorePassword, releaseHandle);
    }

    private record RoleInfo(boolean canLogin, boolean currentUser) { }

    /**
     * Redshift gives every user CREATE and USAGE on the public schema, whereas PostgreSQL 15+
     * revokes CREATE on it from PUBLIC. The grant is per database, and to PUBLIC so that users
     * created with CREATE USER are covered as well as auto-created IAM users.
     */
    private static void grantPublicSchemaAccess(Connection connection) throws SQLException {
        executeSql(connection, "GRANT USAGE, CREATE ON SCHEMA public TO PUBLIC");
    }

    private RoleInfo ensureLoginRole(Connection connection, String roleName, boolean autoCreate) throws SQLException {
        if (roleName.startsWith("pg_")) {
            throw new SQLException("role \"" + roleName + "\" is not permitted for IAM session handover", "28000");
        }
        RoleInfo info = queryRoleInfo(connection, roleName);
        if (info == null) {
            if (!autoCreate) {
                throw new SQLException("role \"" + roleName + "\" does not exist", "28000");
            }
            createLoginRole(connection, roleName);
            info = queryRoleInfo(connection, roleName);
            if (info == null) {
                throw new SQLException("role \"" + roleName + "\" could not be created", "28000");
            }
        }
        if (!info.canLogin()) {
            throw new SQLException("role \"" + roleName + "\" is not permitted to log in", "28000");
        }
        return info;
    }

    private void createLoginRole(Connection connection, String roleName) throws SQLException {
        String sql = "CREATE ROLE " + quoteIdentifier(roleName)
                + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE";
        try {
            executeSql(connection, sql);
        } catch (SQLException failure) {
            // Two concurrent logins with AutoCreate=true may race on CREATE ROLE.
            String state = failure.getSQLState();
            if (!"42710".equals(state) && !"23505".equals(state)) {
                throw failure;
            }
        }
    }

    private List<String> resolveTemporaryGroups(Connection connection, List<String> dbGroups) throws SQLException {
        if (dbGroups == null || dbGroups.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String group : dbGroups) {
            if (group != null && !group.isBlank()) {
                distinct.add(group);
            }
        }
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<String> validGroups = new ArrayList<>();
        for (String group : distinct) {
            if (!roleExists(connection, group)) {
                throw new SQLException("role \"" + group + "\" does not exist", "42704");
            }
            validGroups.add(group);
        }
        return validGroups;
    }

    private static RoleInfo queryRoleInfo(Connection connection, String roleName) throws SQLException {
        String sql = "SELECT rolcanlogin, rolname = current_user FROM pg_catalog.pg_roles WHERE rolname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new RoleInfo(rs.getBoolean(1), rs.getBoolean(2));
            }
        }
    }

    private static boolean roleExists(Connection connection, String roleName) throws SQLException {
        String sql = "SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isDirectMember(Connection connection, String roleName,
                                          String groupName) throws SQLException {
        String sql = "SELECT 1 FROM pg_catalog.pg_auth_members m "
                + "JOIN pg_catalog.pg_roles r ON r.oid = m.roleid "
                + "JOIN pg_catalog.pg_roles u ON u.oid = m.member "
                + "WHERE r.rolname = ? AND u.rolname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, groupName);
            ps.setString(2, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String readPasswordHash(Connection connection, String roleName) throws SQLException {
        String sql = "SELECT rolpassword FROM pg_catalog.pg_authid WHERE rolname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setPassword(Connection connection, String roleName, String password) throws SQLException {
        String literal = password == null ? "NULL" : "'" + password.replace("'", "''") + "'";
        executeSql(connection, "ALTER ROLE " + quoteIdentifier(roleName) + " PASSWORD " + literal);
    }

    private static void releaseGroups(TempGroupGrantTracker.Lease lease, String roleName,
                                      ConnectionSupplier adminConnectionFactory) {
        try {
            lease.release(group -> {
                try (Connection admin = adminConnectionFactory.open()) {
                    executeSql(admin, "REVOKE " + quoteIdentifier(group) + " FROM " + quoteIdentifier(roleName));
                }
            });
        } catch (SQLException | RuntimeException failure) {
            LOG.warnv(failure, "Failed to revoke temporary DbGroups for Redshift role {0}", roleName);
        }
    }

    private static void releasePassword(TempRolePasswordTracker.Lease lease, String roleName,
                                        ConnectionSupplier adminConnectionFactory) {
        if (lease == null) {
            return;
        }
        lease.release(storedHash -> {
            try (Connection admin = adminConnectionFactory.open()) {
                setPassword(admin, roleName, storedHash);
            }
        });
    }

    private static void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
