package io.github.hectorvent.floci.core.common;

/**
 * The S3 canonical user ID an account is identified by in bucket and object ownership, ACL
 * grantees and bucket-policy principals: a 64-character lowercase hex string. AWS derives it
 * from the account and never publishes how, so Floci derives its own value as the SHA-256 hex
 * digest of the account ID: the same account answers the same ID across calls, restarts and
 * storage modes, and two accounts never share one.
 */
public final class AwsCanonicalUserId {

    private AwsCanonicalUserId() {
    }

    public static String forAccount(String accountId) {
        return Digests.sha256Hex(accountId);
    }
}
