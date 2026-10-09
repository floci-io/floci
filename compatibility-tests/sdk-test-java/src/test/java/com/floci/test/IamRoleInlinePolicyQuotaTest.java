package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.LimitExceededException;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamRoleInlinePolicyQuotaTest {

    private static final int ROLE_INLINE_POLICY_QUOTA = 10_240;
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;

    @Test
    void enforcesAggregateNonWhitespaceQuotaAndPreservesAcceptedPolicies() {
        String roleName = TestFixtures.uniqueName("sdk-role-inline-quota");
        String exactLimitPolicy = policyWithNonWhitespaceLength(ROLE_INLINE_POLICY_QUOTA);
        String whitespacePaddedPolicy = exactLimitPolicy.substring(0, 1)
                + " \n\t\r ".repeat(1_000)
                + exactLimitPolicy.substring(1);

        try (IamClient iam = TestFixtures.iamClient()) {
            createRole(iam, roleName);
            try {
                putRolePolicy(iam, roleName, "exact-limit", whitespacePaddedPolicy);

                assertThatThrownBy(() -> putRolePolicy(iam, roleName, "exact-limit",
                        policyWithNonWhitespaceLength(ROLE_INLINE_POLICY_QUOTA + 1)))
                        .isInstanceOf(LimitExceededException.class)
                        .satisfies(error -> assertThat(((LimitExceededException) error).statusCode()).isEqualTo(409));

                assertThat(iam.getRolePolicy(GetRolePolicyRequest.builder()
                                .roleName(roleName)
                                .policyName("exact-limit")
                                .build())
                        .policyDocument())
                        .satisfies(readBack -> assertThat(removeCountedWhitespace(readBack)).isEqualTo(exactLimitPolicy));
            } finally {
                deleteRolePolicies(iam, roleName);
                deleteRole(iam, roleName);
            }
        }
    }

    @Test
    void rejectsAggregateQuotaOverflowAcrossPolicies() {
        String roleName = TestFixtures.uniqueName("sdk-role-inline-aggregate-quota");

        try (IamClient iam = TestFixtures.iamClient()) {
            createRole(iam, roleName);
            try {
                putRolePolicy(iam, roleName, "first", policyWithNonWhitespaceLength(6_000));
                putRolePolicy(iam, roleName, "second", policyWithNonWhitespaceLength(4_041));

                assertThatThrownBy(() -> putRolePolicy(iam, roleName, "third", policyWithNonWhitespaceLength(200)))
                        .isInstanceOf(LimitExceededException.class)
                        .satisfies(error -> assertThat(((LimitExceededException) error).statusCode()).isEqualTo(409));
            } finally {
                deleteRolePolicies(iam, roleName);
                deleteRole(iam, roleName);
            }
        }
    }

    private static void createRole(IamClient iam, String roleName) {
        iam.createRole(CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(TRUST_POLICY)
                .build());
    }

    private static void putRolePolicy(IamClient iam, String roleName, String policyName, String policyDocument) {
        iam.putRolePolicy(PutRolePolicyRequest.builder()
                .roleName(roleName)
                .policyName(policyName)
                .policyDocument(policyDocument)
                .build());
    }

    private static void deleteRolePolicies(IamClient iam, String roleName) {
        for (String policyName : iam.listRolePolicies(ListRolePoliciesRequest.builder()
                .roleName(roleName)
                .build()).policyNames()) {
            iam.deleteRolePolicy(DeleteRolePolicyRequest.builder()
                    .roleName(roleName)
                    .policyName(policyName)
                    .build());
        }
    }

    private static void deleteRole(IamClient iam, String roleName) {
        iam.deleteRole(DeleteRoleRequest.builder().roleName(roleName).build());
    }

    private static String policyWithNonWhitespaceLength(int targetLength) {
        String prefix = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"";
        String suffix = "\",\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        return prefix + "x".repeat(targetLength - prefix.length() - suffix.length()) + suffix;
    }

    private static String removeCountedWhitespace(String policyDocument) {
        return policyDocument.replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "");
    }
}
