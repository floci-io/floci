package io.github.hectorvent.floci.core.common;

import java.util.Optional;

/**
 * Port that maps an STS temporary access key ID (e.g. {@code ASIA...}) to the AWS
 * account the request should resolve to.
 *
 * <p>Declared in {@code core.common} so {@link AccountContextFilter} can route
 * assumed-role and other temporary credentials to the correct account without
 * {@code core} depending on the {@code services.iam} package (which owns the
 * session store) — keeping the dependency direction services → core.
 */
public interface SessionAccountLookup {

    /**
     * Returns the account ID associated with the given temporary access key ID,
     * or {@link Optional#empty()} if it is not a known, live session.
     */
    Optional<String> resolveAccountId(String accessKeyId);
}
