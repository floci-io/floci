package io.floci.aws.core.common;

/**
 * Port that tells {@link AccountContextFilter} whether a request's account has been closed, without
 * {@code core} depending on the {@code services.organizations} package that closes accounts.
 */
public interface ClosedAccountLookup {

    /** Whether {@code accountId} is closed, so that AWS no longer accepts its credentials or sessions. */
    boolean isClosed(String accountId);
}
