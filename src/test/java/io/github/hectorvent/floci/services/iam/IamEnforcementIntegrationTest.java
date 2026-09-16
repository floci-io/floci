package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-style tests for the IAM enforcement engine components:
 * {@link IamPolicyEvaluator}, {@link IamActionRegistry}, and glob matching.
 *
 * The full HTTP enforcement path (filter → evaluator) is covered by the SDK
 * compatibility test {@code IamEnforcementTest.java} in sdk-test-java.
 */
@QuarkusTest
class IamEnforcementIntegrationTest {

    @Inject
    IamPolicyEvaluator evaluator;

    @Inject
    IamConditionContextResolver conditionContextResolver;

    @Inject
    Ec2Service ec2Service;

    @Inject
    S3Service s3Service;

    // =========================================================================
    // IamPolicyEvaluator — basic allow / deny / implicit-deny
    // =========================================================================

    @Test
    void allowMatchingAction() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::my-bucket/key"));
    }

    @Test
    void implicitDenyWhenNoPolicies() {
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(), "s3:GetObject", "arn:aws:s3:::my-bucket/key"));
    }

    @Test
    void implicitDenyWhenNoMatchingStatement() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}
            ]}""";
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::my-bucket/key"));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String allow = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:*","Resource":"*"}
            ]}""";
        String deny = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Deny","Action":"s3:GetObject","Resource":"*"}
            ]}""";
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(allow, deny), "s3:GetObject", "arn:aws:s3:::bucket/key"));
    }

    @Test
    void wildcardActionMatchesService() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:*","Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "s3:DeleteObject", "arn:aws:s3:::bucket/key"));
    }

    @Test
    void fullyWildcardPolicyAllowsAnything() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"*","Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "lambda:InvokeFunction",
                        "arn:aws:lambda:us-east-1:000000000000:function:my-fn"));
    }

    @Test
    void resourceArnPatternMatchesBucket() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::my-bucket/*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::my-bucket/sub/key.txt"));
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::other-bucket/key"));
    }

    @Test
    void resourceArnPatternMatchesDynamoDbTable() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem",
               "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "dynamodb:GetItem",
                        "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable"));
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(policy), "dynamodb:GetItem",
                        "arn:aws:dynamodb:us-east-1:000000000000:table/OtherTable"));
    }

    @Test
    void actionListInStatement() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject"],"Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW, evaluator.evaluate(List.of(policy), "s3:GetObject", "*"));
        assertEquals(Decision.ALLOW, evaluator.evaluate(List.of(policy), "s3:PutObject", "*"));
        assertEquals(Decision.DENY, evaluator.evaluate(List.of(policy), "s3:DeleteObject", "*"));
    }

    @Test
    void malformedPolicyDocumentIsSkipped() {
        // Should not throw; malformed doc is silently ignored
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of("not-json"), "s3:GetObject", "*"));
    }

    @Test
    void conditionContextKeysAreCaseInsensitive() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*",
               "Condition":{"StringEquals":{"aws:SourceIp":"127.0.0.1"}}}
            ]}""";

        assertEquals(Decision.ALLOW,
                evaluator.simulateCustomPolicy(
                        List.of(policy),
                        "s3:GetObject",
                        "arn:aws:s3:::bucket/key",
                        Map.of("AWS:SourceIP", List.of("127.0.0.1"))));
    }

    // =========================================================================
    // IamPolicyEvaluator.globMatches — unit tests
    // =========================================================================

    @Test
    void globMatchesStar() {
        assertTrue(IamPolicyEvaluator.globMatches("s3:*", "s3:GetObject"));
        assertTrue(IamPolicyEvaluator.globMatches("*", "anything"));
        assertFalse(IamPolicyEvaluator.globMatches("s3:*", "lambda:InvokeFunction"));
    }

    @Test
    void globMatchesLiteral() {
        assertTrue(IamPolicyEvaluator.globMatches("s3:GetObject", "s3:GetObject"));
        assertFalse(IamPolicyEvaluator.globMatches("s3:GetObject", "s3:PutObject"));
    }

    @Test
    void globMatchesQuestionMark() {
        assertTrue(IamPolicyEvaluator.globMatches("s3:GetObjec?", "s3:GetObject"));
        assertFalse(IamPolicyEvaluator.globMatches("s3:GetObjec?", "s3:GetObjects"));
    }

    @Test
    void globMatchesCaseInsensitive() {
        assertTrue(IamPolicyEvaluator.globMatches("S3:GetObject", "s3:getobject"));
    }

    @Test
    void globMatchesArnWildcard() {
        assertTrue(IamPolicyEvaluator.globMatches(
                "arn:aws:s3:::my-bucket/*",
                "arn:aws:s3:::my-bucket/sub/dir/file.txt"));
        assertFalse(IamPolicyEvaluator.globMatches(
                "arn:aws:s3:::my-bucket/*",
                "arn:aws:s3:::other-bucket/file.txt"));
    }

    // =========================================================================
    // DynamoDB fine-grained access control (issue #2926)
    // =========================================================================

    private static final String LEADING_KEYS_SCOPED_POLICY = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:Query"],
           "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
           "Condition":{"ForAllValues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
        ]}""";

    @Test
    void leadingKeysConditionAllowsTheInScopeItemAndDeniesTheOutOfScopeOne() {
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_SCOPED_POLICY), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_SCOPED_POLICY), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));

        // The leading key could not be resolved from the request: fail closed.
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_SCOPED_POLICY), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of()));
    }

    @Test
    void wildcardActionAndResourcePolicyStillAllowsRegardlessOfLeadingKeys() {
        // Control: the condition is what discriminates above, not the action or the ARN.
        String wildcard = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"*","Resource":"*"}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(wildcard), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));
    }

    // =========================================================================
    // aws:ResourceTag and aws:RequestTag, resolved from live EC2 and S3 state
    // =========================================================================

    private static final String TEAM_SCOPED_EC2_POLICY = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":"ec2:DeleteTags","Resource":"*",
           "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
        ]}""";

    private static final String OWNER_SCOPED_S3_POLICY = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":"s3:GetBucketTagging","Resource":"*",
           "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"alice"}}}
        ]}""";

    @Test
    void resourceTagConditionFollowsTheTagsEc2ActuallyHoldsForTheResource() {
        Vpc tagged = ec2Service.createVpc("us-east-1", "10.42.0.0/16", false);
        Vpc other = ec2Service.createVpc("us-east-1", "10.43.0.0/16", false);
        ec2Service.createTags("us-east-1", List.of(tagged.getVpcId()), List.of(new Tag("Team", "payments")));
        ec2Service.createTags("us-east-1", List.of(other.getVpcId()), List.of(new Tag("Team", "engineering")));

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(TEAM_SCOPED_EC2_POLICY), "ec2:DeleteTags", "*",
                conditionContextResolver.resolve("ec2", "ec2:DeleteTags",
                        ec2Request("Action=DeleteTags&ResourceId.1=" + tagged.getVpcId() + "&Tag.1.Key=Team"))));

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(TEAM_SCOPED_EC2_POLICY), "ec2:DeleteTags", "*",
                conditionContextResolver.resolve("ec2", "ec2:DeleteTags",
                        ec2Request("Action=DeleteTags&ResourceId.1=" + other.getVpcId() + "&Tag.1.Key=Team"))));
    }

    @Test
    void aMultiResourceRequestIsAuthorizedOncePerResource() {
        Vpc payments = ec2Service.createVpc("us-east-1", "10.44.0.0/16", false);
        Vpc engineering = ec2Service.createVpc("us-east-1", "10.45.0.0/16", false);
        ec2Service.createTags("us-east-1", List.of(payments.getVpcId()), List.of(new Tag("Team", "payments")));
        ec2Service.createTags("us-east-1", List.of(engineering.getVpcId()), List.of(new Tag("Team", "engineering")));
        String mixed = "Action=DeleteTags&ResourceId.1=" + payments.getVpcId()
                + "&ResourceId.2=" + engineering.getVpcId() + "&Tag.1.Key=Team";

        // The permitted resource comes first, yet the second target still fails the policy.
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(TEAM_SCOPED_EC2_POLICY), "ec2:DeleteTags", "*",
                conditionContextResolver.resolve("ec2", "ec2:DeleteTags", ec2Request(mixed))));
        List<Map<String, List<String>>> remaining =
                conditionContextResolver.resolveRemainingTargets("ec2", "ec2:DeleteTags", ec2Request(mixed));
        assertEquals(1, remaining.size());
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(TEAM_SCOPED_EC2_POLICY), "ec2:DeleteTags", "*", remaining.get(0)));
    }

    @Test
    void resourceTagConditionFollowsTheTagsS3ActuallyHoldsForTheBucket() {
        String alices = "iam-tag-ctx-" + UUID.randomUUID().toString().substring(0, 8);
        String bobs = "iam-tag-ctx-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(alices, "us-east-1");
        s3Service.createBucket(bobs, "us-east-1");
        s3Service.putBucketTagging(alices, Map.of("Owner", "alice"));
        s3Service.putBucketTagging(bobs, Map.of("Owner", "bob"));

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(OWNER_SCOPED_S3_POLICY), "s3:GetBucketTagging", "arn:aws:s3:::" + alices,
                conditionContextResolver.resolve("s3", "s3:GetBucketTagging", s3Request("/" + alices))));

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(OWNER_SCOPED_S3_POLICY), "s3:GetBucketTagging", "arn:aws:s3:::" + bobs,
                conditionContextResolver.resolve("s3", "s3:GetBucketTagging", s3Request("/" + bobs))));
    }

    private static ContainerRequestContext ec2Request(String form) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMediaType()).thenReturn(MediaType.valueOf("application/x-www-form-urlencoded"));
        when(request.getEntityStream())
                .thenReturn(new ByteArrayInputStream(form.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private static ContainerRequestContext s3Request(String path) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        return request;
    }
}
