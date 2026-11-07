package io.floci.aws.services.dsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cluster policies as DSQL reads and keeps them. Every expected text is what GetClusterPolicy
 * answered on AWS for the same document, and every refusal and public verdict was measured
 * (eu-west-1, 2026-10-20).
 */
class DsqlClusterPoliciesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String DENY_OUTSIDE_VPC = "\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"*\"},"
            + "\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\","
            + "\"Condition\":{\"StringNotEquals\":{\"aws:SourceVpc\":\"vpc-0123456789abcdef0\"}}";

    @Test
    void aPolicyIsKeptInDsqlsForm() {
        assertEquals("""
                {
                  "Version" : "2012-10-17",
                  "Statement" : [ {
                    "Sid" : "One",
                    "Effect" : "Deny",
                    "Principal" : {
                      "AWS" : "*"
                    },
                    "Action" : "dsql:DbConnect",
                    "Resource" : "*",
                    "Condition" : {
                      "StringNotEquals" : {
                        "aws:SourceVpc" : "vpc-0123456789abcdef0"
                      }
                    }
                  } ]
                }""", kept("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"One\",\"Effect\":\"Deny\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":[\"dsql:DbConnect\"],\"Resource\":\"*\",\"Condition\":"
                + "{\"StringNotEquals\":{\"aws:SourceVpc\":\"vpc-0123456789abcdef0\"}}}]}"));
    }

    @Test
    void aStatementObjectBecomesAListAndItsMembersTakeTheirOrder() {
        assertEquals("""
                {
                  "Version" : "2012-10-17",
                  "Statement" : [ {
                    "Sid" : "Two",
                    "Effect" : "Deny",
                    "Principal" : "*",
                    "Action" : [ "dsql:DbConnect", "dsql:DbConnectAdmin" ],
                    "Resource" : "*",
                    "Condition" : {
                      "StringNotEquals" : {
                        "aws:SourceVpc" : "vpc-0123456789abcdef0"
                      }
                    }
                  } ]
                }""", kept("{\"Statement\":{\"Effect\":\"Deny\",\"Sid\":\"Two\",\"Principal\":\"*\","
                + "\"Action\":[\"dsql:DbConnect\",\"dsql:DbConnectAdmin\"],\"Resource\":[\"*\"],\"Condition\":"
                + "{\"StringNotEquals\":{\"aws:SourceVpc\":[\"vpc-0123456789abcdef0\"]}}},\"Version\":\"2012-10-17\"}"));
    }

    @Test
    void conditionsAreKeptAsTextInTheOrderAHashMapHoldsThem() {
        assertEquals("""
                {
                  "Version" : "2012-10-17",
                  "Statement" : [ {
                    "Sid" : "B",
                    "Effect" : "Deny",
                    "Principal" : "*",
                    "Action" : "dsql:DbConnect",
                    "Resource" : "*",
                    "Condition" : {
                      "Bool" : {
                        "aws:SecureTransport" : "false"
                      },
                      "StringNotEquals" : {
                        "aws:PrincipalAccount" : "111122223333",
                        "aws:SourceVpc" : [ "vpc-0123456789abcdef0", "vpc-0123456789abcdef1" ]
                      },
                      "NumericLessThan" : {
                        "aws:MultiFactorAuthAge" : "3600"
                      }
                    }
                  } ]
                }""", kept("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"B\",\"Effect\":\"Deny\","
                + "\"Principal\":\"*\",\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\",\"Condition\":{"
                + "\"StringNotEquals\":{\"aws:SourceVpc\":[\"vpc-0123456789abcdef0\",\"vpc-0123456789abcdef1\"],"
                + "\"aws:PrincipalAccount\":\"111122223333\"},\"Bool\":{\"aws:SecureTransport\":false},"
                + "\"NumericLessThan\":{\"aws:MultiFactorAuthAge\":3600}}}]}"));
    }

    @Test
    void anAccountIdBecomesItsRootAndAMissingVersionTheOlderOne() {
        String kept = kept("{\"Statement\":[{\"Effect\":\"deny\",\"Principal\":{\"AWS\":[\"" + ACCOUNT + "\"]},"
                + "\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"}]}");

        assertEquals("{\"Version\":\"2008-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":"
                + "\"arn:aws:iam::" + ACCOUNT + ":root\"},\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"}]}",
                compact(kept));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void aDocumentIsRefusedAsDsqlRefusesIt(String measured, String policy, String message) {
        AwsException refused = assertThrows(AwsException.class, () -> kept(policy));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(message, refused.getMessage());
    }

    static Stream<Arguments> refusals() {
        String unreadable = DsqlClusterPolicies.UNREADABLE;
        String invalid = DsqlClusterPolicies.INVALID;
        return Stream.of(
                Arguments.of("not JSON", "not json", unreadable),
                Arguments.of("a JSON array", "[]", invalid),
                Arguments.of("no Statement", "{\"Version\":\"2012-10-17\"}", invalid),
                Arguments.of("an empty Statement", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", invalid),
                Arguments.of("a Version of no year", document("{" + DENY_OUTSIDE_VPC + "}", "2099-01-01"), invalid),
                Arguments.of("a member of no document", "{\"Version\":\"2012-10-17\",\"Foo\":\"x\",\"Statement\":[{"
                        + DENY_OUTSIDE_VPC + "}]}", invalid),
                Arguments.of("a member of no statement", document("{\"Foo\":\"x\"," + DENY_OUTSIDE_VPC + "}"), invalid),
                Arguments.of("no Effect", document("{\"Principal\":\"*\",\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"}"),
                        invalid),
                Arguments.of("an Effect of neither kind", document("{\"Effect\":\"Maybe\",\"Principal\":\"*\","
                        + "\"Resource\":\"*\"}"), invalid),
                Arguments.of("no Principal", document("{\"Effect\":\"Deny\",\"Resource\":\"*\"}"), unreadable),
                Arguments.of("NotPrincipal", document("{\"Effect\":\"Deny\",\"NotPrincipal\":{\"AWS\":\"*\"},"
                        + "\"Resource\":\"*\"}"), unreadable),
                Arguments.of("no Resource", document("{\"Effect\":\"Deny\",\"Principal\":\"*\"}"), unreadable),
                Arguments.of("NotResource", document("{\"Effect\":\"Deny\",\"Principal\":\"*\",\"NotResource\":\"*\"}"),
                        unreadable),
                Arguments.of("a principal of text", document("{\"Effect\":\"Deny\",\"Principal\":\"foo\",\"Resource\":\"*\"}"),
                        invalid),
                Arguments.of("every service", document("{\"Effect\":\"Deny\",\"Principal\":{\"Service\":\"*\"},"
                        + "\"Resource\":\"*\"}"), invalid),
                Arguments.of("a service", document("{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"*\",\"Service\":"
                        + "\"dsql.amazonaws.com\"},\"Resource\":\"*\"}"), DsqlClusterPolicies.SERVICE_PRINCIPAL),
                Arguments.of("a canonical user", document("{\"Effect\":\"Deny\",\"Principal\":{\"CanonicalUser\":\"abc\"},"
                        + "\"Resource\":\"*\"}"), invalid),
                Arguments.of("a role that does not exist", document("{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":"
                        + "\"arn:aws:iam::" + ACCOUNT + ":role/missing\"},\"Resource\":\"*\"}"), invalid),
                Arguments.of("an operator of no case", document("{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Resource\":\"*\","
                        + "\"Condition\":{\"stringequals\":{\"aws:SourceVpc\":\"vpc-1\"}}}"), invalid),
                Arguments.of("an operator of no kind", document("{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Resource\":\"*\","
                        + "\"Condition\":{\"Foo\":{\"aws:SourceVpc\":\"vpc-1\"}}}"), invalid));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowsToEveryone")
    void anAllowToEveryoneIsPublicUnlessAConditionRestrictsIt(String condition, boolean isPublic) {
        String policy = document("{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"dsql:DbConnect\","
                + "\"Resource\":\"*\"" + (condition == null ? "" : ",\"Condition\":" + condition) + "}");

        assertEquals(isPublic, DsqlClusterPolicies.grantsPublicAccess(MAPPER, kept(policy)));
    }

    static Stream<Arguments> allowsToEveryone() {
        String role = "arn:aws:iam::" + ACCOUNT + ":role/x";
        return Stream.of(
                Arguments.of(null, true),
                Arguments.of("{\"StringEquals\":{\"aws:SourceVpc\":\"vpc-1\"}}", false),
                Arguments.of("{\"StringNotEquals\":{\"aws:SourceVpc\":\"vpc-1\"}}", true),
                Arguments.of("{\"StringEqualsIfExists\":{\"aws:SourceVpc\":\"vpc-1\"}}", true),
                Arguments.of("{\"ForAnyValue:StringEquals\":{\"aws:SourceVpc\":\"vpc-1\"}}", false),
                Arguments.of("{\"ForAllValues:StringEquals\":{\"aws:SourceVpc\":\"vpc-1\"}}", true),
                Arguments.of("{\"StringEqualsIgnoreCase\":{\"aws:SourceVpc\":\"vpc-1\"}}", false),
                Arguments.of("{\"StringEquals\":{\"AWS:SOURCEVPC\":\"vpc-1\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceVpc\":[\"vpc-1\",\"*\"]}}", false),
                Arguments.of("{\"StringLike\":{\"aws:SourceVpc\":\"vpc-*\"}}", true),
                Arguments.of("{\"StringLike\":{\"aws:SourceVpc\":[\"vpc-1\",\"vpc-2\"]}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalAccount\":\"*\"}}", false),
                Arguments.of("{\"StringLike\":{\"aws:PrincipalAccount\":\"*\"}}", true),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalOrgID\":\"o-abc\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalOrgPaths\":\"o-a/r-b/ou-c/\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceAccount\":\"" + ACCOUNT + "\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceOwner\":\"" + ACCOUNT + "\"}}", true),
                Arguments.of("{\"StringEquals\":{\"aws:userid\":\"AIDAEXAMPLE\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceVpce\":\"vpce-1\"}}", false),
                Arguments.of("{\"StringLike\":{\"aws:SourceArn\":\"*\"}}", true),
                Arguments.of("{\"StringLike\":{\"aws:SourceArn\":\"" + role + "\"}}", false),
                Arguments.of("{\"StringLike\":{\"aws:SourceArn\":\"arn:aws:iam::" + ACCOUNT + ":role/*\"}}", false),
                Arguments.of("{\"StringLike\":{\"aws:SourceArn\":\"arn:aws:iam::*:role/x\"}}", true),
                Arguments.of("{\"StringLike\":{\"aws:PrincipalArn\":\"*\"}}", true),
                Arguments.of("{\"ArnLike\":{\"aws:SourceArn\":\"*\"}}", false),
                Arguments.of("{\"ArnLike\":{\"aws:SourceArn\":\"arn:aws:*\"}}", false),
                Arguments.of("{\"ArnLike\":{\"aws:PrincipalArn\":\"*\"}}", false),
                Arguments.of("{\"ArnLike\":{\"aws:PrincipalArn\":\"arn:aws:iam::*:role/x\"}}", true),
                Arguments.of("{\"ArnLike\":{\"aws:PrincipalArn\":\"" + role + "\"}}", false),
                Arguments.of("{\"ArnEquals\":{\"aws:SourceArn\":\"*\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceIp\":\"10.0.0.1\"}}", false),
                Arguments.of("{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}", false),
                Arguments.of("{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/7\"}}", true),
                Arguments.of("{\"IpAddress\":{\"aws:SourceIp\":\"0.0.0.0/0\"}}", true),
                Arguments.of("{\"NotIpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}", true),
                Arguments.of("{\"Bool\":{\"aws:SecureTransport\":\"true\"}}", true),
                Arguments.of("{\"Bool\":{\"aws:PrincipalIsAWSService\":\"true\"}}", false),
                Arguments.of("{\"Bool\":{\"aws:PrincipalIsAWSService\":\"false\"}}", true),
                Arguments.of("{\"StringEquals\":{\"aws:SourceVpc\":\"vpc-1\"},\"Bool\":{\"aws:SecureTransport\":\"true\"}}",
                        false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceVpcArn\":\"arn:aws:ec2:eu-west-1:" + ACCOUNT + ":vpc/vpc-1\"}}",
                        false),
                Arguments.of("{\"StringEquals\":{\"aws:VpceAccount\":\"" + ACCOUNT + "\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:VpceOrgID\":\"o-abc\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:VpceOrgPaths\":\"o-a/r-b/ou-c/\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalServiceName\":\"lambda.amazonaws.com\"}}", false),
                Arguments.of("{\"ForAnyValue:StringEquals\":{\"aws:PrincipalServiceNamesList\":\"lambda.amazonaws.com\"}}",
                        false),
                Arguments.of("{\"StringEquals\":{\"aws:Ec2InstanceSourceVpc\":\"vpc-1\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceOrgID\":\"o-abc\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:SourceOrgPaths\":\"o-a/r-b/ou-c/\"}}", false),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalArn\":\"${aws:PrincipalArn}\"}}", true),
                Arguments.of("{\"StringEquals\":{\"aws:SourceVpc\":\"${aws:SourceVpc}\"}}", true),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalArn\":\"arn:aws:iam::" + ACCOUNT + ":role/${aws:username}\"}}",
                        true),
                Arguments.of("{\"StringEquals\":{\"aws:PrincipalAccount\":[\"" + ACCOUNT + "\",\"${aws:PrincipalAccount}\"]}}",
                        true),
                Arguments.of("{\"ArnEquals\":{\"aws:SourceArn\":\"${aws:SourceArn}\"}}", false));
    }

    @Test
    void onlyAWildcardOfTheAwsKindGrantsToEveryone() {
        String allow = "\"Effect\":\"Allow\",\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"";

        assertEquals(true, isPublic(document("{" + allow + ",\"Principal\":{\"AWS\":[\"*\",\"" + ACCOUNT + "\"]}}")));
        assertEquals(false, isPublic(document("{" + allow + ",\"Principal\":{\"Federated\":\"*\"}}")));
        assertEquals(false, isPublic(document("{" + allow + ",\"Principal\":{\"AWS\":\"" + ACCOUNT + "\"}}")));
        assertEquals(true, isPublic("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\","
                + "\"Action\":\"dsql:DbConnect\",\"Resource\":\"*\"},{" + allow + ",\"Principal\":\"*\"}]}"));
    }

    private static boolean isPublic(String policy) {
        return DsqlClusterPolicies.grantsPublicAccess(MAPPER, kept(policy));
    }

    private static String kept(String policy) {
        return DsqlClusterPolicies.canonical(MAPPER, policy, "aws",
                arn -> arn.endsWith(":root") || arn.endsWith(":role/x"));
    }

    private static String document(String statement) {
        return document(statement, "2012-10-17");
    }

    private static String document(String statement, String version) {
        return "{\"Version\":\"" + version + "\",\"Statement\":[" + statement + "]}";
    }

    private static String compact(String json) {
        try {
            return MAPPER.readTree(json).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
