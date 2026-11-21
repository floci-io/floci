package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

/**
 * Under IAM enforcement a CloudFormation request is checked on the stack or the stack set it names, at the ARN
 * CloudFormation minted for it, whether the request names it by its name or by that ARN; a stack instance is checked
 * on its stack set in the caller's account. A grant scoped to one reaches it and no other.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFormationIamIntegrationTest {

    private static final String ACCOUNT = "000000000473";
    private static final String REGION = "us-east-1";
    private static final String TEMPLATE =
            "{\"Resources\":{\"Handle\":{\"Type\":\"AWS::CloudFormation::WaitConditionHandle\"}}}";

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aStackScopedGrantReachesThatStackByNameOrIdAndNoOther() {
        String mine = name();
        String other = name();
        String stackId = stack(mine);
        stack(other);
        assertThat(stackId, matchesPattern("arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":stack/" + mine
                + "/[0-9a-f-]+"));
        String user = user(allow(stackId, "cloudformation:DescribeStacks", "cloudformation:GetTemplate"));

        call(user, "DescribeStacks", Map.of("StackName", mine)).statusCode(200)
                .body(containsString("<StackId>" + stackId + "</StackId>"));
        call(user, "DescribeStacks", Map.of("StackName", stackId)).statusCode(200);
        call(user, "GetTemplate", Map.of("StackName", mine)).statusCode(200);
        denied(call(user, "DescribeStacks", Map.of("StackName", other)), "cloudformation:DescribeStacks");
    }

    @Test
    void aStackSetIsCheckedOnTheArnCloudFormationGivesIt() {
        String mine = name();
        String other = name();
        stackSet(mine);
        stackSet(other);
        String arn = call(ACCOUNT, "DescribeStackSet", Map.of("StackSetName", mine)).statusCode(200)
                .extract().xmlPath().getString("DescribeStackSetResponse.DescribeStackSetResult.StackSet.StackSetARN");
        assertThat(arn, matchesPattern("arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":stackset/" + mine
                + ":[0-9a-f-]+"));
        String user = user(allow(arn, "cloudformation:DescribeStackSet", "cloudformation:DescribeStackInstance"));

        call(user, "DescribeStackSet", Map.of("StackSetName", mine)).statusCode(200);
        denied(call(user, "DescribeStackSet", Map.of("StackSetName", other)), "cloudformation:DescribeStackSet");
        // A stack instance is checked on its stack set, in the caller's account whatever account the instance is in.
        call(user, "DescribeStackInstance", Map.of("StackSetName", mine, "StackInstanceAccount", "111122223333",
                "StackInstanceRegion", REGION)).statusCode(not(equalTo(403)));
        denied(call(user, "DescribeStackInstance", Map.of("StackSetName", other, "StackInstanceAccount",
                "111122223333", "StackInstanceRegion", REGION)), "cloudformation:DescribeStackInstance");
    }

    /** A resource-tag condition reads the tags CloudFormation holds on the stack or the stack set a request names. */
    @Test
    void aResourceTagConditionReadsTheTagsOfTheStackAndTheStackSet() {
        String tagged = name();
        String untagged = name();
        call(ACCOUNT, "CreateStack", Map.of("StackName", tagged, "TemplateBody", TEMPLATE,
                "Tags.member.1.Key", "team", "Tags.member.1.Value", "data")).statusCode(200);
        cleanup.register(() -> call(ACCOUNT, "DeleteStack", Map.of("StackName", tagged)));
        stack(untagged);
        String taggedSet = name();
        String untaggedSet = name();
        call(ACCOUNT, "CreateStackSet", Map.of("StackSetName", taggedSet, "TemplateBody", TEMPLATE,
                "Tags.member.1.Key", "team", "Tags.member.1.Value", "data")).statusCode(200);
        cleanup.register(() -> call(ACCOUNT, "DeleteStackSet", Map.of("StackSetName", taggedSet)));
        stackSet(untaggedSet);
        String user = user("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "[\"cloudformation:DescribeStacks\",\"cloudformation:DescribeStackSet\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}]}");

        call(user, "DescribeStacks", Map.of("StackName", tagged)).statusCode(200);
        denied(call(user, "DescribeStacks", Map.of("StackName", untagged)), "cloudformation:DescribeStacks");
        call(user, "DescribeStackSet", Map.of("StackSetName", taggedSet)).statusCode(200);
        denied(call(user, "DescribeStackSet", Map.of("StackSetName", untaggedSet)), "cloudformation:DescribeStackSet");
    }

    private String stack(String name) {
        String id = call(ACCOUNT, "CreateStack", Map.of("StackName", name, "TemplateBody", TEMPLATE)).statusCode(200)
                .extract().xmlPath().getString("CreateStackResponse.CreateStackResult.StackId");
        cleanup.register(() -> call(ACCOUNT, "DeleteStack", Map.of("StackName", name)));
        return id;
    }

    private void stackSet(String name) {
        call(ACCOUNT, "CreateStackSet", Map.of("StackSetName", name, "TemplateBody", TEMPLATE)).statusCode(200);
        cleanup.register(() -> call(ACCOUNT, "DeleteStackSet", Map.of("StackSetName", name)));
    }

    private static String name() {
        return "iam-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String allow(String resource, String... actions) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\""
                + String.join("\",\"", actions) + "\"],\"Resource\":\"" + resource + "\"}]}";
    }

    private static void denied(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString("AccessDenied")).body(containsString(action));
    }

    private String user(String policy) {
        String name = "cfn-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = auth(ACCOUNT, "iam");
        String accessKeyId = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, name)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, name, "p", policy);
        return accessKeyId;
    }

    private ValidatableResponse call(String accessKeyId, String action, Map<String, String> params) {
        RequestSpecification request = given().port(endpoint.getPort())
                .header("Authorization", auth(accessKeyId, "cloudformation"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-15");
        params.forEach(request::formParam);
        return request.when().post("/").then();
    }
}
