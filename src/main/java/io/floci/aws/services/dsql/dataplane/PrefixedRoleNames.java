package io.floci.aws.services.dsql.dataplane;

import java.util.Set;

/**
 * Role names of a cluster that shares its PostgreSQL server with others: each backend role
 * carries the cluster's prefix, {@code d<clusterid>_}, so clusters' roles do not collide. Role
 * specifiers, written as unquoted keywords, and PostgreSQL's reserved {@code pg_} roles keep their
 * names; a quoted {@code "public"} names a role like any other, and is prefixed.
 */
public final class PrefixedRoleNames implements RoleNames {

    /** PostgreSQL's longest identifier, in bytes (NAMEDATALEN - 1); a longer one is truncated. */
    static final int MAX_IDENTIFIER_BYTES = 63;

    /**
     * The longest logical name, in UTF-8 bytes, whose prefixed form fits in PostgreSQL's 63-byte
     * identifier, with the 28-byte prefix of a 26-character cluster id ({@code d} + id + {@code _}).
     */
    static final int MAX_LOGICAL = 35;

    private static final Set<String> SPECIFIERS = Set.of(
            "public", "current_user", "current_role", "session_user", "none", "default");

    private final String prefix;

    public PrefixedRoleNames(String clusterId) {
        this.prefix = "d" + clusterId + "_";
    }

    @Override
    public String toBackend(String logical) {
        if (logical.startsWith("pg_")) {
            return logical;
        }
        return prefix + logical;
    }

    @Override
    public String toBackend(String logical, boolean quoted) {
        if (!quoted && SPECIFIERS.contains(logical)) {
            return logical;
        }
        return toBackend(logical);
    }

    @Override
    public String toLogical(String backend) {
        return backend.startsWith(prefix) ? backend.substring(prefix.length()) : backend;
    }

    @Override
    public boolean isIdentity() {
        return false;
    }
}
