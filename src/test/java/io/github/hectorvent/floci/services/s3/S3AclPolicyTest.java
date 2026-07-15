package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3AclPolicyTest {

    @Test
    void parsesGrantFromAccessControlList() {
        S3AclPolicy policy = parse(acl(groupGrant(S3AclPolicy.ALL_USERS_GROUP_URI, "READ")));

        assertEquals(1, policy.grants().size());
        S3AclPolicy.Grant grant = policy.grants().getFirst();
        assertEquals(S3AclPolicy.ALL_USERS_GROUP_URI, grant.grantee().uri());
        assertEquals(S3AclPolicy.Permission.READ, grant.permission());
        assertTrue(grant.allowsPublicRead());
    }

    @Test
    void parsesNamespacedAcl() {
        String grant = groupGrant(S3AclPolicy.ALL_USERS_GROUP_URI, "FULL_CONTROL");
        S3AclPolicy policy = parse("""
                <AccessControlPolicy xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <AccessControlList>
                    %s
                  </AccessControlList>
                </AccessControlPolicy>
                """.formatted(grant));

        assertEquals(1, policy.grants().size());
        assertEquals(S3AclPolicy.Permission.FULL_CONTROL, policy.grants().getFirst().permission());
        assertTrue(policy.grants().getFirst().allowsPublicRead());
    }

    @Test
    void ignoresGrantOutsideAccessControlList() {
        String grant = groupGrant(S3AclPolicy.ALL_USERS_GROUP_URI, "READ");
        S3AclPolicy policy = parse("""
                <AccessControlPolicy>
                  <AccessControlList/>
                  <Metadata>
                    %s
                  </Metadata>
                </AccessControlPolicy>
                """.formatted(grant));

        assertTrue(policy.grants().isEmpty());
    }

    @Test
    void returnsEmptyPolicyForUnsupportedShape() {
        assertTrue(parse("<NotAnAcl/>").grants().isEmpty());
        assertTrue(parse("<AccessControlPolicy/>").grants().isEmpty());
    }

    @Test
    void mapsUnknownPermission() {
        S3AclPolicy policy = parse(acl(groupGrant(S3AclPolicy.ALL_USERS_GROUP_URI, "DELETE")));

        assertEquals(S3AclPolicy.Permission.UNKNOWN, policy.grants().getFirst().permission());
    }

    @Test
    void rejectsMalformedAcl() {
        assertInvalidAcl("<AccessControlPolicy>");
    }

    @Test
    void rejectsDoctype() {
        assertInvalidAcl("""
                <!DOCTYPE AccessControlPolicy [
                  <!ENTITY external SYSTEM "file:///etc/passwd">
                ]>
                <AccessControlPolicy>
                  <AccessControlList/>
                </AccessControlPolicy>
                """);
    }

    private static void assertInvalidAcl(String acl) {
        assertThrows(S3AclPolicy.AclParseException.class, () -> S3AclPolicy.parse(acl));
    }

    private static S3AclPolicy parse(String acl) {
        try {
            return S3AclPolicy.parse(acl);
        } catch (S3AclPolicy.AclParseException e) {
            throw new AssertionError(e);
        }
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
