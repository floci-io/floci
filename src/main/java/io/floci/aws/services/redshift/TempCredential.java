package io.floci.aws.services.redshift;

import java.time.Instant;
import java.util.List;

public record TempCredential(
        String dbUser,
        String password,
        Instant expiresAt,
        List<String> dbGroups,
        String databaseRole,
        boolean autoCreate) {
    public TempCredential(String dbUser, String password, Instant expiresAt, List<String> dbGroups) {
        this(dbUser, password, expiresAt, dbGroups, dbUser, true);
    }
}
