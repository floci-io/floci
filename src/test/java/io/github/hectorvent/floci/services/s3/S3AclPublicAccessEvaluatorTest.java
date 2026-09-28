package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3AclPublicAccessEvaluatorTest {

    private static final String AUTHENTICATED_USERS_GROUP_URI =
            "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";

    @Test
    void allUsersReadAclAllowsPublicRead() {
        assertTrue(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "READ"))));
    }

    @Test
    void allUsersFullControlAclAllowsPublicRead() {
        assertTrue(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "FULL_CONTROL"))));
    }

    @Test
    void allUsersWriteAclDoesNotAllowPublicRead() {
        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "WRITE"))));
    }

    @Test
    void authenticatedUsersReadAclDoesNotAllowPublicRead() {
        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl(
                groupGrant(AUTHENTICATED_USERS_GROUP_URI, "READ"))));
    }

    @Test
    void grantsDoNotCombineAcrossAclEntries() {
        String acl = acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "WRITE"),
                groupGrant(AUTHENTICATED_USERS_GROUP_URI, "READ"));

        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl));
    }

    @Test
    void grantsOutsideAccessControlListDoNotAllowPublicRead() {
        String acl = """
                <AccessControlPolicy>
                  <AccessControlList>
                  </AccessControlList>
                  <Metadata>
                    %s
                  </Metadata>
                </AccessControlPolicy>
                """.formatted(groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "READ"));

        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl));
    }

    @Test
    void namespaceAttributesDoNotBlockPublicReadAcl() {
        String acl = """
                <AccessControlPolicy xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <AccessControlList>
                    <Grant>
                      <Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="Group">
                        <URI>%s</URI>
                      </Grantee>
                      <Permission>READ</Permission>
                    </Grant>
                  </AccessControlList>
                </AccessControlPolicy>
                """.formatted(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI);

        assertTrue(S3AclPublicAccessEvaluator.aclAllowsPublicRead(acl));
    }

    @Test
    void invalidAclDoesNotAllowPublicRead() {
        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead(null));
        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead(""));
        assertFalse(S3AclPublicAccessEvaluator.aclAllowsPublicRead("<AccessControlPolicy>"));
    }

    // Block Public Access asks a broader question than grant evaluation does: an ACL counts as
    // public when it grants ANY permission to AllUsers or to AuthenticatedUsers, so a
    // WRITE_ACP grant and an AuthenticatedUsers grant are both public here while neither
    // grants an anonymous caller a read.

    @Test
    void allUsersReadAclIsPublic() {
        assertTrue(S3AclPublicAccessEvaluator.aclIsPublic(acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "READ"))));
    }

    @Test
    void authenticatedUsersAclIsPublic() {
        assertTrue(S3AclPublicAccessEvaluator.aclIsPublic(acl(
                groupGrant(AUTHENTICATED_USERS_GROUP_URI, "READ"))));
    }

    @Test
    void anyPermissionToAPublicGroupIsPublic() {
        assertTrue(S3AclPublicAccessEvaluator.aclIsPublic(acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "WRITE_ACP"))));
        assertTrue(S3AclPublicAccessEvaluator.aclIsPublic(acl(
                groupGrant(S3AclPublicAccessEvaluator.ALL_USERS_GROUP_URI, "READ_ACP"))));
    }

    @Test
    void logDeliveryGroupAclIsNotPublic() {
        assertFalse(S3AclPublicAccessEvaluator.aclIsPublic(acl(groupGrant(
                "http://acs.amazonaws.com/groups/s3/LogDelivery", "WRITE"))));
    }

    @Test
    void canonicalUserOnlyAclIsNotPublic() {
        String acl = """
                <AccessControlPolicy>
                  <AccessControlList>
                    <Grant>
                      <Grantee xsi:type="CanonicalUser"
                               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <ID>000000000000</ID>
                      </Grantee>
                      <Permission>FULL_CONTROL</Permission>
                    </Grant>
                  </AccessControlList>
                </AccessControlPolicy>
                """;

        assertFalse(S3AclPublicAccessEvaluator.aclIsPublic(acl));
    }

    @Test
    void invalidAclIsNotPublic() {
        assertFalse(S3AclPublicAccessEvaluator.aclIsPublic(null));
        assertFalse(S3AclPublicAccessEvaluator.aclIsPublic(""));
        assertFalse(S3AclPublicAccessEvaluator.aclIsPublic("<AccessControlPolicy>"));
    }

    private static String acl(String... grants) {
        return """
                <AccessControlPolicy>
                  <AccessControlList>
                    %s
                  </AccessControlList>
                </AccessControlPolicy>
                """.formatted(String.join("\n", grants));
    }

    private static String groupGrant(String groupUri, String permission) {
        return """
                <Grant>
                  <Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="Group">
                    <URI>%s</URI>
                  </Grantee>
                  <Permission>%s</Permission>
                </Grant>
                """.formatted(groupUri, permission);
    }
}
