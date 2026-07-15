package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.s3.S3PublicAccessEvaluator.PublicAccessDecision.DENY;
import static io.github.hectorvent.floci.services.s3.S3PublicAccessEvaluator.PublicAccessDecision.NEUTRAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3PublicAccessEvaluatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BUCKET = "public-bucket";
    private static final String BUCKET_ARN = "arn:aws:s3:::" + BUCKET;
    private static final String OBJECT_ARN = BUCKET_ARN + "/folder/object.txt";

    @Test
    void blankPolicyDoesNotAllow() {
        assertFalse(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, "", "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void invalidJsonDoesNotAllow() {
        assertFalse(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, "{not-json", "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void publicStringPrincipalAllowsMatchingObjectAction() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void publicAwsPrincipalAllowsMatchingBucketAction() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":{"AWS":"*"},
                  "Action":["s3:ListBucket"],
                  "Resource":["arn:aws:s3:::public-bucket"]
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:ListBucket", BUCKET_ARN));
    }

    @Test
    void publicPrincipalArrayAllowsMatchingAction() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":["arn:aws:iam::123456789012:root","*"],
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void nonPublicPrincipalDoesNotAllow() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::123456789012:root"},
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertEquals(NEUTRAL, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void conditionStatementDoesNotAllowAnonymousRead() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*",
                  "Condition":{"StringEquals":{"aws:PrincipalAccount":"123456789012"}}
                }}""";

        assertEquals(NEUTRAL, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void missingEffectDoesNotAllowAnonymousRead() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Principal":"*",
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertEquals(NEUTRAL, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {
                    "Effect":"Allow",
                    "Principal":"*",
                    "Action":"s3:GetObject",
                    "Resource":"arn:aws:s3:::public-bucket/*"
                  },
                  {
                    "Effect":"Deny",
                    "Principal":"*",
                    "Action":"s3:GetObject",
                    "Resource":"arn:aws:s3:::public-bucket/folder/object.txt"
                  }
                ]}""";

        assertEquals(DENY, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void conditionalDenyFailsClosedAndOverridesAllow() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {
                    "Effect":"Allow",
                    "Principal":"*",
                    "Action":"s3:GetObject",
                    "Resource":"arn:aws:s3:::public-bucket/*"
                  },
                  {
                    "Effect":"Deny",
                    "Principal":"*",
                    "Action":"s3:GetObject",
                    "Resource":"arn:aws:s3:::public-bucket/*",
                    "Condition":{"StringEquals":{"aws:SourceVpc":"vpc-123"}}
                  }
                ]}""";

        assertEquals(DENY, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void wildcardActionAndResourceMatch() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:Get*",
                  "Resource":"arn:aws:s3:::public-bucket/folder/*"
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void notActionAllowsWhenRequestedActionIsNotExcluded() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "NotAction":"s3:PutObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void notActionDoesNotAllowWhenRequestedActionIsExcluded() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "NotAction":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertEquals(NEUTRAL, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void notResourceAllowsWhenRequestedResourceIsNotExcluded() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:GetObject",
                  "NotResource":"arn:aws:s3:::other-bucket/*"
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void notResourceDoesNotAllowWhenRequestedResourceIsExcluded() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:GetObject",
                  "NotResource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertEquals(NEUTRAL, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void notPrincipalAllowsAnonymousWhenOnlySpecificPrincipalIsExcluded() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "NotPrincipal":{"AWS":"arn:aws:iam::123456789012:root"},
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertTrue(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void notPrincipalWildcardDoesNotAllowAnonymous() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "NotPrincipal":"*",
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertEquals(NEUTRAL, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void denyWithNotPrincipalOverridesAllowWhenAnonymousIsNotExcluded() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {
                    "Effect":"Allow",
                    "Principal":"*",
                    "Action":"s3:GetObject",
                    "Resource":"arn:aws:s3:::public-bucket/*"
                  },
                  {
                    "Effect":"Deny",
                    "NotPrincipal":{"AWS":"arn:aws:iam::123456789012:root"},
                    "Action":"s3:GetObject",
                    "Resource":"arn:aws:s3:::public-bucket/*"
                  }
                ]}""";

        assertEquals(DENY, S3PublicAccessEvaluator.publicPolicyDecision(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void actionMismatchDoesNotAllow() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:PutObject",
                  "Resource":"arn:aws:s3:::public-bucket/*"
                }}""";

        assertFalse(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void resourceMismatchDoesNotAllow() {
        String policy = """
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow",
                  "Principal":"*",
                  "Action":"s3:GetObject",
                  "Resource":"arn:aws:s3:::other-bucket/*"
                }}""";

        assertFalse(S3PublicAccessEvaluator.publicPolicyAllows(
                OBJECT_MAPPER, policy, "s3:GetObject", OBJECT_ARN));
    }

    @Test
    void arnHelpersBuildBucketAndObjectArns() {
        assertEquals(BUCKET_ARN, S3PublicAccessEvaluator.bucketArn(BUCKET));
        assertEquals(OBJECT_ARN, S3PublicAccessEvaluator.objectArn(BUCKET, "folder/object.txt"));
    }
}
