package io.github.hectorvent.floci.services.redshift;

import java.time.Instant;
import java.util.List;

public record TempCredential(String dbUser, String password, Instant expiresAt, List<String> dbGroups) {
}
