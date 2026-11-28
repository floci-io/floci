package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KmsPolicyPrincipalsTest {

    private static final String ACCOUNT = "000000000000";

    @Test
    void refusesMissingUsersAndRolesAcrossPartitions() {
        for (String partition : List.of("aws", "aws-cn", "aws-us-gov")) {
            for (String resource : List.of("role/team/missing", "user/team/missing")) {
                String arn = AwsArnUtils.Arn.global(partition, "iam", ACCOUNT, resource).toString();
                AwsException error = assertThrows(AwsException.class,
                        () -> KmsPolicyPrincipals.requireExisting(policy(arn), ACCOUNT, value -> false));
                assertEquals("MalformedPolicyDocumentException", error.getErrorCode());
                assertEquals(400, error.getHttpStatus());
            }
        }
    }

    @Test
    void looksUpExistingPathPrincipals() {
        String arn = AwsArnUtils.Arn.global("aws", "iam", ACCOUNT, "role/team/held").toString();
        List<String> lookedUp = new ArrayList<>();
        assertDoesNotThrow(() -> KmsPolicyPrincipals.requireExisting(policy(arn), ACCOUNT, value -> {
            lookedUp.add(value);
            return true;
        }));
        assertEquals(List.of(arn), lookedUp);
    }

    @Test
    void acceptsNonLocalAndNonIdentityPrincipalsWithoutLookup() {
        String root = AwsArnUtils.Arn.global("aws", "iam", ACCOUNT, "root").toString();
        String otherAccount = AwsArnUtils.Arn.global("aws", "iam", "123456789012", "role/other").toString();
        for (String principal : List.of("*", ACCOUNT, root, otherAccount)) {
            assertDoesNotThrow(() -> KmsPolicyPrincipals.requireExisting(policy(principal), ACCOUNT, value -> {
                throw new AssertionError("Unexpected IAM lookup: " + value);
            }));
        }
    }

    @Test
    void checksEveryStatementAndArrayPrincipal() {
        String missing = AwsArnUtils.Arn.global("aws", "iam", ACCOUNT, "user/missing").toString();
        String policy = "{\"Statement\":[{\"Principal\":{\"Service\":\"lambda.amazonaws.com\"}},"
                + "{\"Principal\":{\"AWS\":[\"*\",\"" + missing + "\"]}}]}";
        assertThrows(AwsException.class,
                () -> KmsPolicyPrincipals.requireExisting(policy, ACCOUNT, value -> false));
    }

    private static String policy(String principal) {
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + principal
                + "\"},\"Action\":\"kms:*\",\"Resource\":\"*\"}}";
    }
}
