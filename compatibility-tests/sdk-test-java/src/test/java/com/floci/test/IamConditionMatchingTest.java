package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.ContextEntry;
import software.amazon.awssdk.services.iam.model.ContextKeyTypeEnum;
import software.amazon.awssdk.services.iam.model.PolicyEvaluationDecisionType;
import software.amazon.awssdk.services.iam.model.SimulateCustomPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulateCustomPolicyResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IAM condition matching through the policy simulator")
class IamConditionMatchingTest {

    private static IamClient iam;

    @BeforeAll
    static void setup() {
        iam = TestFixtures.iamClient();
    }

    @AfterAll
    static void cleanup() {
        if (iam != null) {
            iam.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "StringLike, home/Alice/*, home/Alice/notes, ALLOWED",
            "StringLike, home/Alice/*, home/alice/notes, IMPLICIT_DENY",
            "StringNotLike, home/Alice/*, home/Alice/notes, IMPLICIT_DENY",
            "StringNotLike, home/Alice/*, home/alice/notes, ALLOWED",
            "ArnEquals, arn:aws:sns:*:*:Alerts, arn:aws:sns:us-east-1:111122223333:Alerts, ALLOWED",
            "ArnEquals, arn:aws:sns:*:*:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, IMPLICIT_DENY",
            "ArnLike, arn:aws:sns:*:*:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, IMPLICIT_DENY",
            "ArnNotEquals, arn:aws:sns:*:*:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, ALLOWED",
            "ArnNotLike, arn:aws:sns:*:*:Alerts, arn:aws:sns:us-east-1:111122223333:alerts, ALLOWED",
            "ArnLike, arn:aws:lambda:us-east-1:*:worker, arn:aws:lambda:us-east-1:111122223333:function:worker, IMPLICIT_DENY",
            "ArnNotLike, arn:aws:lambda:us-east-1:*:worker, arn:aws:lambda:us-east-1:111122223333:function:worker, ALLOWED",
            "ArnLike, arn:aws:lambda:us-east-1:*:function:worker:*, arn:aws:lambda:us-east-1:111122223333:function:worker:live, ALLOWED",
            "ArnLike, arn:aws:s3:::Reports/*, arn:aws:s3:::Reports/quarter:a, ALLOWED"
    })
    void simulatorPreservesCaseAndArnComponentBoundaries(String operator, String pattern,
                                                         String value, PolicyEvaluationDecisionType expected) {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*",
                   "Condition":{"%s":{"aws:SourceArn":"%s"}}}
                ]}
                """.formatted(operator, pattern);
        SimulateCustomPolicyResponse response = iam.simulateCustomPolicy(SimulateCustomPolicyRequest.builder()
                .policyInputList(policy)
                .actionNames("sqs:SendMessage")
                .contextEntries(ContextEntry.builder()
                        .contextKeyName("aws:SourceArn")
                        .contextKeyValues(value)
                        .contextKeyType(ContextKeyTypeEnum.STRING)
                        .build())
                .build());

        assertThat(response.evaluationResults()).hasSize(1);
        assertThat(response.evaluationResults().get(0).evalDecision()).isEqualTo(expected);
    }
}
