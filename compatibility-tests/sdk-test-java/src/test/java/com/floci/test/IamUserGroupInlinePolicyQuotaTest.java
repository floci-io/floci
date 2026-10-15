package com.floci.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.LimitExceededException;

import java.util.List;

import static com.floci.test.IamInlinePolicyTestHelper.policyWithNonWhitespaceLength;
import static com.floci.test.IamInlinePolicyTestHelper.removeCountedWhitespace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamUserGroupInlinePolicyQuotaTest {

    @ParameterizedTest
    @CsvSource({"true, 2048", "false, 5120"})
    void enforcesAggregateQuotaAndPreservesRejectedWrites(boolean user, int limit) {
        String name = TestFixtures.uniqueName("sdk-inline-quota");
        try (IamClient iam = TestFixtures.iamClient()) {
            if (user) {
                iam.createUser(request -> request.userName(name));
            } else {
                iam.createGroup(request -> request.groupName(name));
            }
            try {
                String exact = policyWithNonWhitespaceLength(limit);
                String padded = exact.substring(0, 1) + " \n\t\r ".repeat(500) + exact.substring(1);
                put(iam, user, name, "first", padded);
                assertThatThrownBy(() -> put(iam, user, name, "first", policyWithNonWhitespaceLength(limit + 1)))
                        .isInstanceOf(LimitExceededException.class)
                        .satisfies(error -> assertThat(((LimitExceededException) error).statusCode()).isEqualTo(409));
                assertThat(removeCountedWhitespace(get(iam, user, name, "first"))).isEqualTo(exact);

                String half = policyWithNonWhitespaceLength(limit / 2);
                put(iam, user, name, "first", half);
                put(iam, user, name, "second", half);
                assertThatThrownBy(() -> put(iam, user, name, "third", policyWithNonWhitespaceLength(200)))
                        .isInstanceOf(LimitExceededException.class)
                        .satisfies(error -> assertThat(((LimitExceededException) error).statusCode()).isEqualTo(409));
                assertThat(list(iam, user, name)).containsExactlyInAnyOrder("first", "second");
                assertThat(get(iam, user, name, "first")).isEqualTo(half);
                assertThat(get(iam, user, name, "second")).isEqualTo(half);
            } finally {
                for (String policy : list(iam, user, name)) {
                    if (user) {
                        iam.deleteUserPolicy(request -> request.userName(name).policyName(policy));
                    } else {
                        iam.deleteGroupPolicy(request -> request.groupName(name).policyName(policy));
                    }
                }
                if (user) {
                    iam.deleteUser(request -> request.userName(name));
                } else {
                    iam.deleteGroup(request -> request.groupName(name));
                }
            }
        }
    }

    private static void put(IamClient iam, boolean user, String name, String policy, String document) {
        if (user) {
            iam.putUserPolicy(request -> request.userName(name).policyName(policy).policyDocument(document));
        } else {
            iam.putGroupPolicy(request -> request.groupName(name).policyName(policy).policyDocument(document));
        }
    }

    private static String get(IamClient iam, boolean user, String name, String policy) {
        return user ? iam.getUserPolicy(request -> request.userName(name).policyName(policy)).policyDocument()
                : iam.getGroupPolicy(request -> request.groupName(name).policyName(policy)).policyDocument();
    }

    private static List<String> list(IamClient iam, boolean user, String name) {
        return user ? iam.listUserPolicies(request -> request.userName(name)).policyNames()
                : iam.listGroupPolicies(request -> request.groupName(name)).policyNames();
    }
}
