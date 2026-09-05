package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.LimitExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("CloudWatch Logs cross-account management APIs")
class CloudWatchLogsCrossAccountPolicyTest {
    private static final String DESTINATION_NAME = "floci-sdk-cross-account-destination";
    private static final String POLICY_NAME = "floci-sdk-cross-account-transformer";

    @Test
    @DisplayName("uses AWS SDK models for destination and account policy operations")
    void destinationAndAccountPolicyOperationsUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only destination ARNs and persistent test resources");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            var putDestination = logs.putDestination(request -> request
                    .destinationName(DESTINATION_NAME)
                    .targetArn("arn:aws:kinesis:us-east-1:000000000000:stream/floci-sdk-logs")
                    .roleArn("arn:aws:iam::000000000000:role/floci-sdk-logs"));

            assertThat(putDestination.destination()).isNotNull();
            assertThat(putDestination.destination().destinationName()).isEqualTo(DESTINATION_NAME);
            assertThat(putDestination.destination().arn()).contains(":logs:us-east-1:");

            logs.putDestinationPolicy(request -> request
                    .destinationName(DESTINATION_NAME)
                    .accessPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[]}"));

            var putPolicy = logs.putAccountPolicy(request -> request
                    .policyName(POLICY_NAME)
                    .policyType("TRANSFORMER_POLICY")
                    .policyDocument("[{\"parseJSON\":{}}]")
                    .selectionCriteria("LogGroupNamePrefix = \"/floci/sdk-cross-account/\"")
                    .scope("ALL"));

            assertThat(putPolicy.accountPolicy()).isNotNull();
            assertThat(putPolicy.accountPolicy().policyName()).isEqualTo(POLICY_NAME);
            assertThat(putPolicy.accountPolicy().policyTypeAsString()).isEqualTo("TRANSFORMER_POLICY");

            var policies = logs.describeAccountPolicies(request -> request.policyType("TRANSFORMER_POLICY"));
            assertThat(policies.accountPolicies())
                    .anySatisfy(policy -> {
                        assertThat(policy.policyName()).isEqualTo(POLICY_NAME);
                        assertThat(policy.selectionCriteria())
                                .isEqualTo("LogGroupNamePrefix = \"/floci/sdk-cross-account/\"");
                    });

            var fieldIndex = logs.putAccountPolicy(request -> request
                    .policyName("floci-sdk-field-index-quoted-value")
                    .policyType("FIELD_INDEX_POLICY")
                    .policyDocument("{\"Fields\":[\"requestId\"]}")
                    .selectionCriteria("LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"")
                    .scope("ALL"));

            assertThat(fieldIndex.accountPolicy().selectionCriteria())
                    .isEqualTo("LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"");

            assertThatThrownBy(() -> logs.putAccountPolicy(request -> request
                    .policyName("floci-sdk-field-index-global")
                    .policyType("FIELD_INDEX_POLICY")
                    .policyDocument("{\"Fields\":[\"requestId\"]}")
                    .scope("ALL")))
                    .isInstanceOfSatisfying(LimitExceededException.class, error ->
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("LimitExceededException"));
        }
    }

    @Test
    @DisplayName("returns the modeled SDK error for mixed field-index selection criteria")
    void mixedFieldIndexCriteriaReturnsSdkInvalidParameterException() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids mutating account-level policies in real AWS");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            assertThatThrownBy(() -> logs.putAccountPolicy(request -> request
                    .policyName("floci-sdk-invalid-mixed-index")
                    .policyType("FIELD_INDEX_POLICY")
                    .policyDocument("{\"Fields\":[\"requestId\"]}")
                    .selectionCriteria("LogGroupNamePrefix = \"/floci/\" AND DataSourceName = \"amazon_vpc\" AND DataSourceType = \"flow\"")
                    .scope("ALL")))
                    .isInstanceOfSatisfying(InvalidParameterException.class, error ->
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterException"));
        }
    }
}
