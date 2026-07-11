package io.github.hectorvent.floci.services.s3;

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
}
