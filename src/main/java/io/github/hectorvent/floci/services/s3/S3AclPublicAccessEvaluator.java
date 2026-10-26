package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import org.jboss.logging.Logger;

final class S3AclPublicAccessEvaluator {

    static final String ALL_USERS_GROUP_URI = S3AclPolicy.ALL_USERS_GROUP_URI;

    private static final Logger LOG = Logger.getLogger(S3AclPublicAccessEvaluator.class);

    private S3AclPublicAccessEvaluator() {
    }

    static boolean aclAllowsPublicRead(String acl) {
        if (acl == null || acl.isBlank()) {
            return false;
        }
        try {
            S3AclPolicy policy = S3AclPolicy.parse(acl);
            return policy.grants().stream().anyMatch(S3AclPolicy.Grant::allowsPublicRead);
        } catch (S3AclPolicy.AclParseException e) {
            LOG.debugv(e, "Failed to parse S3 ACL for public read evaluation");
            return false;
        }
    }

    /**
     * Whether Block Public Access considers this ACL public: it grants any permission to the
     * AllUsers or AuthenticatedUsers predefined groups. {@code BlockPublicAcls} rejects a write
     * that submits such an ACL, and {@code IgnorePublicAcls} suppresses one already stored.
     */
    static boolean aclIsPublic(String acl) {
        if (acl == null || acl.isBlank()) {
            return false;
        }
        try {
            S3AclPolicy policy = S3AclPolicy.parse(acl);
            return policy.grants().stream().anyMatch(S3AclPolicy.Grant::grantsToPublicGroup);
        } catch (S3AclPolicy.AclParseException e) {
            LOG.debugv(e, "Failed to parse S3 ACL for public status evaluation");
            return false;
        }
    }

    static boolean aclAllowsPublicWrite(String acl) {
        if (acl == null || acl.isBlank()) {
            return false;
        }
        try {
            S3AclPolicy policy = S3AclPolicy.parse(acl);
            return policy.grants().stream().anyMatch(S3AclPolicy.Grant::allowsPublicWrite);
        } catch (S3AclPolicy.AclParseException e) {
            LOG.debugv(e, "Failed to parse S3 ACL for public write evaluation");
            return false;
        }
    }

    /**
     * Whether an ACL grants anyone other than the owner account: every grant must name the
     * owner, whose canonical user ID is what generated ACLs carry and whose account ID is
     * accepted for ACLs written before Floci derived one. An ACL with no grants names nobody,
     * so ownership enforcement rejects it too.
     */
    static boolean aclGrantsNonOwner(String acl, String ownerAccount) {
        if (acl == null || acl.isBlank()) {
            return false;
        }
        try {
            S3AclPolicy policy = S3AclPolicy.parse(acl);
            if (policy.grants().isEmpty()) {
                return true;
            }
            String canonicalId = AwsCanonicalUserId.forAccount(ownerAccount);
            return policy.grants().stream()
                    .anyMatch(grant -> !grant.grantee().isCanonicalUser(canonicalId)
                            && !grant.grantee().isCanonicalUser(ownerAccount));
        } catch (S3AclPolicy.AclParseException e) {
            LOG.debugv(e, "Failed to parse S3 ACL for ownership enforcement");
            return true;
        }
    }
}
