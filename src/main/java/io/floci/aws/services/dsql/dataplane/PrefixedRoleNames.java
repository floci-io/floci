package io.floci.aws.services.dsql.dataplane;

import java.util.Set;

/**
 * Role names of a cluster that shares its PostgreSQL server with others: each backend role
 * carries the cluster's prefix, {@code d<clusterid>_}, so clusters' roles do not collide. Role
 * specifiers and PostgreSQL's reserved {@code pg_} roles keep their names.
 */
public final class PrefixedRoleNames implements RoleNames {

    /**
     * The longest logical name whose prefixed form fits in PostgreSQL's 63-byte identifier, with
     * the prefix of a 26-character cluster id ({@code d} + id + {@code _}).
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
        if (SPECIFIERS.contains(logical) || logical.startsWith("pg_")) {
            return logical;
        }
        return prefix + logical;
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
