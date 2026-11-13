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
import java.util.regex.Pattern;

/**
 * Provisions or verifies the PostgreSQL role and temporary {@code DbGroups} memberships
 * corresponding to an IAM-derived Redshift credential, and switches the session authorization
 * to that non-superuser role.
 */
@ApplicationScoped
public class RedshiftDbIdentityProvisioner {

    private static final Logger LOG = Logger.getLogger(RedshiftDbIdentityProvisioner.class);
    private static final Pattern RESTRICTED_SESSION_COMMAND = Pattern.compile(
            "\\b(?:RESET\\s+SESSION\\s+AUTHORIZATION"
                    + "|SET\\s+(?:SESSION\\s+|LOCAL\\s+)?SESSION\\s+AUTHORIZATION"
                    + "|DISCARD\\s+ALL"
                    + "|RESET\\s+ALL)\\b",
            Pattern.CASE_INSENSITIVE);

    public static boolean isRestrictedSessionCommand(String sql) {
        return sql != null && RESTRICTED_SESSION_COMMAND.matcher(sql).find();
    }

    public record PreparedIdentity(String databaseRole, Runnable releaseLease) { }

    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection open() throws SQLException;
    }

    private final TempGroupGrantTracker grantTracker;

    @Inject
    public RedshiftDbIdentityProvisioner(TempGroupGrantTracker grantTracker) {
        this.grantTracker = grantTracker;
    }

    /**
     * Ensures {@code roleName} exists (creating it when {@code autoCreate} is true), validates
     * every group in {@code dbGroups}, and grants temporary membership for groups the role does
     * not already belong to. Must be called on a master/admin connection.
     */
    public PreparedIdentity prepare(Connection adminConnection, String backendKey,
                                    String roleName, boolean autoCreate, List<String> dbGroups,
                                    ConnectionSupplier adminConnectionFactory) throws SQLException {
        if (roleName == null || roleName.isBlank()) {
            throw new SQLException("Database role name must not be blank", "28000");
        }
        ensureLoginRole(adminConnection, roleName, autoCreate);

        List<String> groupsToGrant = resolveTemporaryGroups(adminConnection, roleName, dbGroups);
        TempGroupGrantTracker.Lease lease = grantTracker.acquire(
                backendKey,
                roleName,
                groupsToGrant,
                group -> executeSql(adminConnection,
                        "GRANT " + quoteIdentifier(group) + " TO " + quoteIdentifier(roleName)),
                group -> executeSql(adminConnection,
                        "REVOKE " + quoteIdentifier(group) + " FROM " + quoteIdentifier(roleName)));

        Runnable releaseHandle = () -> releaseLease(lease, roleName, adminConnectionFactory);
        return new PreparedIdentity(roleName, releaseHandle);
    }

    /**
     * Provisions the target role/groups on {@code connection} (currently authenticated as master)
     * and switches the connection's session authorization to {@code roleName}.
     */
    public Runnable applyToJdbc(Connection connection, String backendKey,
                                String roleName, boolean autoCreate, List<String> dbGroups,
                                ConnectionSupplier adminConnectionFactory) throws SQLException {
        PreparedIdentity prepared = prepare(
                connection, backendKey, roleName, autoCreate, dbGroups, adminConnectionFactory);
        try {
            executeSql(connection, "SET SESSION AUTHORIZATION " + quoteIdentifier(prepared.databaseRole()));
            return prepared.releaseLease();
        } catch (SQLException | RuntimeException failure) {
            prepared.releaseLease().run();
            throw failure;
        }
    }

    private void ensureLoginRole(Connection connection, String roleName, boolean autoCreate) throws SQLException {
        Boolean canLogin = queryRoleCanLogin(connection, roleName);
        if (canLogin == null) {
            if (!autoCreate) {
                throw new SQLException("role \"" + roleName + "\" does not exist", "28000");
            }
            createLoginRole(connection, roleName);
            canLogin = queryRoleCanLogin(connection, roleName);
            if (canLogin == null) {
                throw new SQLException("role \"" + roleName + "\" could not be created", "28000");
            }
        }
        if (!canLogin) {
            throw new SQLException("role \"" + roleName + "\" is not permitted to log in", "28000");
        }
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

    private List<String> resolveTemporaryGroups(Connection connection, String roleName,
                                                List<String> dbGroups) throws SQLException {
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
        List<String> toGrant = new ArrayList<>();
        for (String group : distinct) {
            if (!roleExists(connection, group)) {
                throw new SQLException("role \"" + group + "\" does not exist", "42704");
            }
            if (!isDirectMember(connection, roleName, group)) {
                toGrant.add(group);
            }
        }
        return toGrant;
    }

    private static Boolean queryRoleCanLogin(Connection connection, String roleName) throws SQLException {
        String sql = "SELECT rolcanlogin FROM pg_catalog.pg_roles WHERE rolname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getBoolean(1);
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

    private static void releaseLease(TempGroupGrantTracker.Lease lease, String roleName,
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

    private static void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
