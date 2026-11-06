package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With IAM enforcement on, the role a {@code Fn::GetStackOutput} names has to trust a principal in
 * the consuming account, and its policies have to allow DescribeStacks on the referenced stack. On
 * AWS CloudFormation assumes the role as the stack's execution role, or as the caller, and reads in
 * a session named {@code GetStackOutput} (measured, eu-west-1, 2026-10-16); Floci keeps neither, so
 * a grant to any principal of the consuming account is enough.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFormationGetStackOutputEnforcementIntegrationTest {

    private static final String CONSUMER = "131313131313";
    private static final String OWNER = "242424242424";
    private static final String OTHER = "353535353535";
    private static final String REGION = "us-east-1";
    private static final String DESCRIBE_STACKS = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"cloudformation:DescribeStacks","Resource":"*"}]}""";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final String suffix = Long.toString(System.nanoTime(), 36);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRoleTrustingTheConsumingAccount_readsTheOutput() {
        String producer = producer("vpc-account");
        String roleArn = role("account", allow("{\"AWS\":\"arn:aws:iam::" + CONSUMER + ":root\"}"), DESCRIBE_STACKS);

        String consumer = consume("account", producer, roleArn);

        assertEquals("CREATE_COMPLETE", awaitConsumer(consumer).status());
        parameterValue("account").body("Parameter.Value", equalTo("vpc-account"));
    }

    /** The CDK's role trusts the consuming account's CloudFormation execution role. */
    @Test
    void aRoleTrustingARoleOfTheConsumingAccount_readsTheOutput() {
        String executionRole = "cdk-exec-" + suffix;
        String executionRoleArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), CONSUMER, "/",
                executionRole, DESCRIBE_STACKS);
        String producer = producer("vpc-cdk");
        String roleArn = role("cdk", allow("{\"AWS\":\"" + executionRoleArn + "\"}"), DESCRIBE_STACKS);

        String consumer = consume("cdk", producer, roleArn);

        assertEquals("CREATE_COMPLETE", awaitConsumer(consumer).status());
        parameterValue("cdk").body("Parameter.Value", equalTo("vpc-cdk"));
    }

    @Test
    void aRoleTrustingOnlyAService_isRefused() {
        assertCannotBeAssumed("service", allow("{\"Service\":\"lambda.amazonaws.com\"}"));
    }

    @Test
    void aRoleTrustingAnotherAccount_isRefused() {
        assertCannotBeAssumed("other", allow("{\"AWS\":\"arn:aws:iam::" + OTHER + ":root\"}"));
    }

    @Test
    void aTrustPolicyDenyingTheConsumingAccount_isRefused() {
        String principal = "{\"AWS\":\"arn:aws:iam::" + CONSUMER + ":root\"}";
        assertCannotBeAssumed("denied", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":%1$s,"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":%1$s,"Action":"sts:AssumeRole"}]}""".formatted(principal));
    }

    /** Measured: "because no identity-based policy allows the cloudformation:DescribeStacks action". */
    @Test
    void aRoleWithoutDescribeStacks_isRefusedByDescribeStacks() {
        assertCannotDescribe("no-describe", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""",
                "because no identity-based policy allows the cloudformation:DescribeStacks action");
    }

    @Test
    void aRoleDenyingDescribeStacks_isRefusedByDescribeStacks() {
        assertCannotDescribe("deny-describe", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"cloudformation:DescribeStacks","Resource":"*"}]}""",
                "with an explicit deny in an identity-based policy");
    }

    private void assertCannotBeAssumed(String base, String trustPolicy) {
        String producer = producer("vpc-" + base);
        String roleArn = role(base, trustPolicy, DESCRIBE_STACKS);

        String consumer = consume(base, producer, roleArn);
        CfnStackWaits.StackState state = awaitConsumer(consumer);

        assertEquals("ROLLBACK_COMPLETE", state.status());
        String failure = CfnStackWaits.failedResourceReason(consumer, auth(CONSUMER, REGION, "cloudformation"));
        assertThat(failure, containsString("User: arn:aws:iam::" + CONSUMER + ":root is not authorized to"
                + " perform: sts:AssumeRole on resource: " + roleArn + " (Service: AWSSecurityTokenService; Status"
                + " Code: 403; Error Code: AccessDenied; Request ID: "));
    }

    private void assertCannotDescribe(String base, String policy, String reason) {
        String producer = producer("vpc-" + base);
        String roleArn = role(base, allow("{\"AWS\":\"arn:aws:iam::" + CONSUMER + ":root\"}"), policy);

        String consumer = consume(base, producer, roleArn);
        CfnStackWaits.StackState state = awaitConsumer(consumer);

        assertEquals("ROLLBACK_COMPLETE", state.status());
        String failure = CfnStackWaits.failedResourceReason(consumer, auth(CONSUMER, REGION, "cloudformation"));
        assertThat(failure, containsString("User: arn:aws:sts::" + OWNER + ":assumed-role/" + name(base)
                + "/GetStackOutput is not authorized to perform: cloudformation:DescribeStacks on resource:"
                + " arn:aws:cloudformation:" + REGION + ":" + OWNER + ":stack/" + producer + "/"));
        assertThat(failure, containsString(" " + reason + " (Service: AmazonCloudFormation; Status Code: 403;"
                + " Error Code: AccessDenied; Request ID: "));
    }

    private String name(String base) {
        return "gso-enf-" + base + "-" + suffix;
    }

    private static String allow(String principal) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":" + principal
                + ",\"Action\":\"sts:AssumeRole\"}]}";
    }

    private String role(String base, String trustPolicy, String policy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OWNER, "/", name(base), trustPolicy, policy);
    }

    /** A stack in the owning account whose output VpcId is {@code vpcId}. */
    private String producer(String vpcId) {
        String producer = name("producer-" + vpcId);
        createStack(OWNER, producer, "{\"Outputs\": {\"VpcId\": {\"Value\": \"" + vpcId + "\"}}}");
        assertEquals("CREATE_COMPLETE",
                CfnStackWaits.awaitTerminal(producer, auth(OWNER, REGION, "cloudformation")).status());
        return producer;
    }

    /** A stack in the consuming account copying the producer's VpcId into a parameter, through the role. */
    private String consume(String base, String producer, String roleArn) {
        String consumer = name("consumer-" + base);
        createStack(CONSUMER, consumer, """
                {"Resources": {"Copy": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "%s", "Type": "String",
                  "Value": {"Fn::GetStackOutput": {"StackName": "%s", "OutputName": "VpcId", "RoleArn": "%s"}}}}}}
                """.formatted(parameter(base), producer, roleArn));
        return consumer;
    }

    private static CfnStackWaits.StackState awaitConsumer(String consumer) {
        return CfnStackWaits.awaitTerminal(consumer, auth(CONSUMER, REGION, "cloudformation"));
    }

    private String parameter(String base) {
        return "/gso-enf/" + suffix + "/" + base;
    }

    private void createStack(String account, String stackName, String template) {
        GetStackOutputFixtures.createStack(cleanup, account, REGION, stackName, template);
    }

    private ValidatableResponse parameterValue(String base) {
        return GetStackOutputFixtures.parameterValue(CONSUMER, REGION, parameter(base));
    }
}
