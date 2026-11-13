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
import java.util.Locale;
import java.util.Set;

/**
 * Provisions or verifies the PostgreSQL role and temporary {@code DbGroups} memberships
 * corresponding to an IAM-derived Redshift credential, and switches the session authorization
 * to that non-superuser role.
 */
@ApplicationScoped
public class RedshiftDbIdentityProvisioner {

    private static final Logger LOG = Logger.getLogger(RedshiftDbIdentityProvisioner.class);

    private enum TokenKind {
        WORD,
        STRING,
        PUNCT
    }

    private record SqlToken(TokenKind kind, String value) { }

    public static boolean isRestrictedSessionCommand(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return containsRestrictedStatement(tokenizeSql(sql));
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

        List<String> groupsToGrant = resolveTemporaryGroups(adminConnection, dbGroups);
        TempGroupGrantTracker.Lease lease = grantTracker.acquire(
                backendKey,
                roleName,
                groupsToGrant,
                group -> executeSql(adminConnection,
                        "GRANT " + quoteIdentifier(group) + " TO " + quoteIdentifier(roleName)),
                group -> executeSql(adminConnection,
                        "REVOKE " + quoteIdentifier(group) + " FROM " + quoteIdentifier(roleName)),
                group -> isDirectMember(adminConnection, roleName, group));

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

    private record RoleInfo(boolean canLogin, boolean privileged, boolean currentUser) { }

    private void ensureLoginRole(Connection connection, String roleName, boolean autoCreate) throws SQLException {
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
        if (info.privileged() || info.currentUser()) {
            throw new SQLException("role \"" + roleName + "\" is not permitted for IAM session handover", "28000");
        }
        if (!info.canLogin()) {
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
        String sql = "SELECT rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolbypassrls, "
                + "rolname = current_user FROM pg_catalog.pg_roles WHERE rolname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                boolean canLogin = rs.getBoolean(1);
                boolean privileged = rs.getBoolean(2) || rs.getBoolean(3) || rs.getBoolean(4) || rs.getBoolean(5);
                boolean currentUser = rs.getBoolean(6);
                return new RoleInfo(canLogin, privileged, currentUser);
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

    private static boolean containsRestrictedStatement(List<SqlToken> tokens) {
        int i = 0;
        int n = tokens.size();
        while (i < n) {
            while (i < n && isSemicolon(tokens.get(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int stmtStart = i;
            while (i < n && !isSemicolon(tokens.get(i))) {
                i++;
            }
            int stmtEnd = i;
            if (isStatementRestricted(tokens, stmtStart, stmtEnd)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStatementRestricted(List<SqlToken> tokens, int start, int end) {
        if (isWord(tokens, start, end, "do")) {
            return true;
        }
        for (int k = start; k < end; k++) {
            SqlToken current = tokens.get(k);
            if (current.kind() != TokenKind.WORD) {
                continue;
            }
            String word = current.value();
            if ("reset".equals(word)) {
                if (isWord(tokens, k + 1, end, "all")) {
                    return true;
                }
                if (isWord(tokens, k + 1, end, "session") && isWord(tokens, k + 2, end, "authorization")) {
                    return true;
                }
            } else if ("discard".equals(word)) {
                if (isWord(tokens, k + 1, end, "all")) {
                    return true;
                }
            } else if ("set".equals(word)) {
                int idx = k + 1;
                if (isWord(tokens, idx, end, "session") || isWord(tokens, idx, end, "local")) {
                    if (isWord(tokens, idx, end, "session") && isWord(tokens, idx + 1, end, "authorization")) {
                        return true;
                    }
                    idx++;
                }
                if (isWord(tokens, idx, end, "session_authorization")) {
                    return true;
                }
                if (isWord(tokens, idx, end, "session") && isWord(tokens, idx + 1, end, "authorization")) {
                    return true;
                }
            } else if ("set_config".equals(word) && isPunct(tokens, k + 1, end, "(")) {
                if (isRestrictedSetConfigCall(tokens, k + 2, end)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRestrictedSetConfigCall(List<SqlToken> tokens, int argStart, int end) {
        List<SqlToken> firstArg = new ArrayList<>();
        int depth = 1;
        for (int k = argStart; k < end && depth > 0; k++) {
            SqlToken token = tokens.get(k);
            if (token.kind() == TokenKind.PUNCT) {
                if ("(".equals(token.value())) {
                    depth++;
                } else if (")".equals(token.value())) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                } else if (",".equals(token.value()) && depth == 1) {
                    break;
                }
            }
            firstArg.add(token);
        }
        if (firstArg.size() != 1 || firstArg.get(0).kind() != TokenKind.STRING) {
            return true;
        }
        String gucName = firstArg.get(0).value().trim().toLowerCase(Locale.ROOT);
        return "session_authorization".equals(gucName);
    }

    private static boolean isSemicolon(SqlToken token) {
        return token.kind() == TokenKind.PUNCT && ";".equals(token.value());
    }

    private static boolean isWord(List<SqlToken> tokens, int index, int end, String expected) {
        return index < end
                && tokens.get(index).kind() == TokenKind.WORD
                && expected.equals(tokens.get(index).value());
    }

    private static boolean isPunct(List<SqlToken> tokens, int index, int end, String expected) {
        return index < end
                && tokens.get(index).kind() == TokenKind.PUNCT
                && expected.equals(tokens.get(index).value());
    }

    private static List<SqlToken> tokenizeSql(String sql) {
        List<SqlToken> tokens = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i + 2);
                i = nl < 0 ? len : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i + 2);
                continue;
            }
            if (c == '$') {
                String tag = matchDollarTag(sql, i);
                if (tag != null) {
                    int bodyStart = i + tag.length();
                    int closeIdx = sql.indexOf(tag, bodyStart);
                    if (closeIdx < 0) {
                        tokens.add(new SqlToken(TokenKind.STRING, sql.substring(bodyStart)));
                        i = len;
                    } else {
                        tokens.add(new SqlToken(TokenKind.STRING, sql.substring(bodyStart, closeIdx)));
                        i = closeIdx + tag.length();
                    }
                    continue;
                }
            }
            if (c == '\'') {
                i = readSingleQuotedString(sql, i + 1, false, tokens);
                continue;
            }
            if (isStringPrefix(c) && i + 1 < len && sql.charAt(i + 1) == '\'') {
                boolean escapeMode = c == 'e' || c == 'E';
                i = readSingleQuotedString(sql, i + 2, escapeMode, tokens);
                continue;
            }
            if (c == '"') {
                StringBuilder ident = new StringBuilder();
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (ch == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            ident.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    ident.append(ch);
                    i++;
                }
                tokens.add(new SqlToken(TokenKind.WORD, ident.toString().toLowerCase(Locale.ROOT)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                i++;
                while (i < len) {
                    char ch = sql.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new SqlToken(TokenKind.WORD, sql.substring(start, i).toLowerCase(Locale.ROOT)));
                continue;
            }
            if (c == ';' || c == '(' || c == ')' || c == ',' || c == '.' || c == '=') {
                tokens.add(new SqlToken(TokenKind.PUNCT, String.valueOf(c)));
            }
            i++;
        }
        return tokens;
    }

    private static int skipBlockComment(String sql, int start) {
        int len = sql.length();
        int depth = 1;
        int i = start;
        while (i < len && depth > 0) {
            char c = sql.charAt(i);
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                depth++;
                i += 2;
            } else if (c == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                depth--;
                i += 2;
            } else {
                i++;
            }
        }
        return i;
    }

    private static String matchDollarTag(String sql, int start) {
        int len = sql.length();
        int i = start + 1;
        if (i < len && sql.charAt(i) == '$') {
            return "$$";
        }
        if (i >= len || !(Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            return null;
        }
        i++;
        while (i < len) {
            char ch = sql.charAt(i);
            if (ch == '$') {
                return sql.substring(start, i + 1);
            }
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return null;
            }
            i++;
        }
        return null;
    }

    private static boolean isStringPrefix(char c) {
        return c == 'e' || c == 'E' || c == 'b' || c == 'B' || c == 'x' || c == 'X' || c == 'n' || c == 'N';
    }

    private static int readSingleQuotedString(String sql, int start, boolean escapeMode, List<SqlToken> tokens) {
        int len = sql.length();
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < len) {
            char ch = sql.charAt(i);
            if (escapeMode && ch == '\\') {
                if (i + 1 < len) {
                    sb.append(sql.charAt(i + 1));
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            if (ch == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    sb.append('\'');
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            sb.append(ch);
            i++;
        }
        tokens.add(new SqlToken(TokenKind.STRING, sb.toString()));
        return i;
    }
}
