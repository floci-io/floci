package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.InlinePolicyChange;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

@QuarkusTest
class CloudFormationIamQuotaRollbackIntegrationTest {

    @InjectSpy
    IamService iam;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUserUpdateReportsActualCompensationOutcome(boolean restorationFails) {
        String suffix = UUID.randomUUID().toString();
        String stack = "quota-rollback-" + suffix;
        String user = "quota-user-" + suffix;
        cfn("CreateStack", stack, template(user, 200)).then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String historical = policy(2049);
        iam.getUser(user).getInlinePolicies().put("owned", historical);
        assertEquals(historical, iam.getUserPolicy(user, "owned"));
        if (restorationFails) {
            doAnswer(call -> {
                InlinePolicyChange change = (InlinePolicyChange) call.callRealMethod();
                return new InlinePolicyChange(change.previousDocument(), () -> {
                    throw new AwsException("AccessDenied", "injected compensation failure", 403);
                });
            }).when(iam).deleteUserPolicyWithRollback(user, "owned");
        }
        try {
            cfn("UpdateStack", stack, template(user, 2050)).then().statusCode(200);
            String expected = restorationFails ? "UPDATE_ROLLBACK_FAILED" : "UPDATE_ROLLBACK_COMPLETE";
            CfnStackWaits.awaitStatus(stack, expected);
            cfn("DescribeStackEvents", stack, null).then().statusCode(200)
                    .body(containsString("Maximum policy size of 2048 bytes exceeded"));
            if (restorationFails) {
                assertNull(iam.getUser(user).getInlinePolicies().get("owned"));
                cfn("DescribeStackResources", stack, null).then().statusCode(200)
                        .body(containsString("<ResourceStatus>UPDATE_FAILED</ResourceStatus>"));
                cfn("DescribeStackEvents", stack, null).then().statusCode(200)
                        .body(containsString("Could not restore user after failed update"));
            } else {
                assertEquals(historical, iam.getUserPolicy(user, "owned"));
            }
        } finally {
            doCallRealMethod().when(iam).deleteUserPolicyWithRollback(user, "owned");
            cfn("DeleteStack", stack, null).then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack);
        }
    }

    private static Response cfn(String action, String stack, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private static String template(String user, int size) {
        return "{\"Resources\":{\"User\":{\"Type\":\"AWS::IAM::User\",\"Properties\":{\"UserName\":\""
                + user + "\",\"Policies\":[{\"PolicyName\":\"owned\",\"PolicyDocument\":" + policy(size) + "}]}}}}";
    }

    private static String policy(int length) {
        String prefix = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"";
        String suffix = "\",\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        return prefix + "x".repeat(length - prefix.length() - suffix.length()) + suffix;
    }
}
