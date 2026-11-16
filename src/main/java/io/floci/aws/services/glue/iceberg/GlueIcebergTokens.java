package io.floci.aws.services.glue.iceberg;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer tokens for Floci's own callers of the Glue Iceberg REST endpoint: the DuckDB sidecar that runs Athena's
 * Iceberg statements attaches the endpoint with one (DuckDB signs catalog requests with SigV4 only over HTTPS, and
 * the endpoint accepts OAuth bearer tokens on AWS too). Each token acts as one account in one Region and expires.
 */
@ApplicationScoped
public class GlueIcebergTokens {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Clock clock;

    public GlueIcebergTokens() {
        this(Clock.systemUTC());
    }

    GlueIcebergTokens(Clock clock) {
        this.clock = clock;
    }

    /** A token that acts as {@code accountId} in {@code region} until {@code lifetime} has passed. */
    public String mint(String accountId, String region, Duration lifetime) {
        Instant now = clock.instant();
        grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt()));
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(token, new Grant(accountId, region, now.plus(lifetime)));
        return token;
    }

    /** Gives a token up before it expires, once its caller is done. */
    public void revoke(String token) {
        grants.remove(token);
    }

    /** The account and Region a live token acts as. */
    Optional<Grant> resolve(String token) {
        Grant grant = token == null ? null : grants.get(token);
        return grant != null && clock.instant().isBefore(grant.expiresAt()) ? Optional.of(grant) : Optional.empty();
    }

    record Grant(String accountId, String region, Instant expiresAt) {
    }
}
