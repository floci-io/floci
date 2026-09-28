package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "meaning of public" rules S3 Block Public Access applies to a bucket policy. S3 assumes a
 * policy is public and then looks for a reason it is not: a wildcard principal is public unless
 * the statement pins one of the recognised condition keys to a fixed, wildcard-free value.
 */
class S3PublicPolicyStatusTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void wildcardPrincipalAllowIsPublic() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:PutObject","Effect":"Allow"}]}
                """));
    }

    @Test
    void wildcardAwsPrincipalAllowIsPublic() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":{"AWS":"*"},"Resource":"arn:aws:s3:::b/*",
                "Action":"s3:GetObject","Effect":"Allow"}]}
                """));
    }

    @Test
    void wildcardPrincipalDenyIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:*","Effect":"Deny"}]}
                """));
    }

    @Test
    void fixedPrincipalAllowIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":{"AWS":"arn:aws:iam::111122223333:root"},
                "Resource":"arn:aws:s3:::b/*","Action":"s3:GetObject","Effect":"Allow"}]}
                """));
    }

    @Test
    void servicePrincipalAllowIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":{"Service":"cloudtrail.amazonaws.com"},
                "Resource":"arn:aws:s3:::b/*","Action":"s3:PutObject","Effect":"Allow"}]}
                """));
    }

    @Test
    void wildcardPrincipalPinnedToAFixedSourceVpcIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:PutObject","Effect":"Allow",
                "Condition":{"StringEquals":{"aws:SourceVpc":"vpc-91237329"}}}]}
                """));
    }

    @Test
    void wildcardPrincipalWithAWildcardSourceVpcIsPublic() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:PutObject","Effect":"Allow",
                "Condition":{"StringLike":{"aws:SourceVpc":"vpc-*"}}}]}
                """));
    }

    @Test
    void wildcardPrincipalPinnedToAFixedPrincipalArnIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"ArnEquals":{"aws:PrincipalArn":"arn:aws:iam::111122223333:role/app"}}}]}
                """));
    }

    @Test
    void wildcardPrincipalPinnedToAFixedSourceAccountIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"StringEquals":{"aws:SourceAccount":"111122223333"}}}]}
                """));
    }

    @Test
    void forAnyValueWithFixedPrincipalOrgPathIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"ForAnyValue:StringLike":{"aws:PrincipalOrgPaths":
                ["o-a1b2c3d4e5/r-ab12/ou-ab12-11111111/"]}}}]}
                """));
    }

    @Test
    void forAllValuesOrWildcardOrgPathDoesNotNarrowPublicAccess() {
        for (String operator : new String[] {"ForAllValues:StringLike", "ForAnyValue:StringLike"}) {
            String path = operator.startsWith("ForAllValues")
                    ? "o-a1b2c3d4e5/r-ab12/ou-ab12-11111111/"
                    : "o-a1b2c3d4e5/r-ab12/ou-ab12-11111111/*";
            assertTrue(isPublic("""
                    {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                    "Condition":{"%s":{"aws:PrincipalOrgPaths":["%s"]}}}]}
                    """.formatted(operator, path)));
        }
    }

    @Test
    void accessPointNameWildcardWithFixedAccountIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"arn:aws:s3:::b/*",
                "Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"ArnLike":{"s3:DataAccessPointArn":
                "arn:aws:s3:us-west-2:123456789012:accesspoint/*"}}}]}
                """));
    }

    @Test
    void accessPointArnWithWildcardAccountIsPublic() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"arn:aws:s3:::b/*",
                "Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"ArnLike":{"s3:DataAccessPointArn":
                "arn:aws:s3:us-west-2:*:accesspoint/*"}}}]}
                """));
    }

    @Test
    void negatedOrOptionalConditionDoesNotMakePolicyNonPublic() {
        for (String operator : new String[] {"StringNotEquals", "StringEqualsIfExists", "IpAddress"}) {
            assertTrue(isPublic("""
                    {"Statement":[{"Principal":"*","Resource":"*",
                    "Action":"s3:GetObject","Effect":"Allow",
                    "Condition":{"%s":{"aws:SourceAccount":"123456789012"}}}]}
                    """.formatted(operator)));
        }
    }

    @Test
    void anUnrecognisedConditionKeyDoesNotMakeAWildcardPrincipalPrivate() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"StringEquals":{"s3:x-amz-acl":"bucket-owner-full-control"}}}]}
                """));
    }

    @Test
    void aPolicyVariableIsNotAFixedValue() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"StringEquals":{"aws:userid":"${aws:userid}"}}}]}
                """));
    }

    @Test
    void aNarrowSourceIpRangeIsNotPublic() {
        assertFalse(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"IpAddress":{"aws:SourceIp":"203.0.113.0/24"}}}]}
                """));
    }

    @Test
    void aVeryBroadSourceIpRangeIsPublic() {
        assertTrue(isPublic("""
                {"Statement":[{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow",
                "Condition":{"IpAddress":{"aws:SourceIp":"0.0.0.0/1"}}}]}
                """));
    }

    @Test
    void onePublicStatementMakesTheWholePolicyPublic() {
        assertTrue(isPublic("""
                {"Statement":[
                  {"Principal":{"Service":"cloudtrail.amazonaws.com"},"Resource":"arn:aws:s3:::b/*",
                   "Action":"s3:PutObject","Effect":"Allow"},
                  {"Principal":{"AWS":"arn:aws:iam::444455556666:root"},"Resource":"arn:aws:s3:::b/*",
                   "Action":"s3:GetObject","Effect":"Allow"},
                  {"Principal":"*","Resource":"arn:aws:s3:::b/*","Action":"s3:GetObject","Effect":"Allow"}
                ]}
                """));
    }

    @Test
    void removingThePublicStatementMakesThePolicyNonPublic() {
        assertFalse(isPublic("""
                {"Statement":[
                  {"Principal":{"Service":"cloudtrail.amazonaws.com"},"Resource":"arn:aws:s3:::b/*",
                   "Action":"s3:PutObject","Effect":"Allow"},
                  {"Principal":{"AWS":"arn:aws:iam::444455556666:root"},"Resource":"arn:aws:s3:::b/*",
                   "Action":"s3:GetObject","Effect":"Allow"}
                ]}
                """));
    }

    @Test
    void notPrincipalAllowIsPublic() {
        assertTrue(isPublic("""
                {"Statement":[{"NotPrincipal":{"AWS":"arn:aws:iam::111122223333:root"},
                "Resource":"*","Action":"s3:GetObject","Effect":"Allow"}]}
                """));
    }

    @Test
    void aSingleStatementObjectIsEvaluated() {
        assertTrue(isPublic("""
                {"Statement":{"Principal":"*","Resource":"*","Action":"s3:GetObject","Effect":"Allow"}}
                """));
    }

    @Test
    void anAbsentOrUnparseablePolicyIsNotPublic() {
        assertFalse(isPublic(null));
        assertFalse(isPublic(""));
        assertFalse(isPublic("not json"));
    }

    private boolean isPublic(String policy) {
        return S3PublicAccessEvaluator.policyIsPublic(objectMapper, policy);
    }
}
