package io.github.hectorvent.floci.services.budgets;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class BudgetsIntegrationTest {
    private static final String TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/budgets/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void budgetLifecycle() {
        json("AWSBudgetServiceGateway.CreateBudget", "{\"AccountId\":\"000000000000\",\"Budget\":{\"BudgetName\":\"platform\",\"BudgetType\":\"COST\",\"TimeUnit\":\"MONTHLY\",\"BudgetLimit\":{\"Amount\":\"100\",\"Unit\":\"USD\"}}}")
                .statusCode(200);
        json("AWSBudgetServiceGateway.DescribeBudget", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"platform\"}")
                .statusCode(200).body("Budget.BudgetName", equalTo("platform"));
        json("AWSBudgetServiceGateway.CreateNotification", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"platform\",\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\",\"Threshold\":80},\"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"ops@example.com\"}]}")
                .statusCode(200);
        json("AWSBudgetServiceGateway.DescribeSubscribersForNotification", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"platform\",\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\",\"Threshold\":80}}")
                .statusCode(200).body("Subscribers[0].Address", equalTo("ops@example.com"));
    }


    @Test
    void crossAccountRequestsReturnAccessDenied() {
        String foreign = "210987654321";
        String caller = "123456789012";
        jsonAs(caller, "AWSBudgetServiceGateway.DescribeBudget",
                "{\"AccountId\":\"" + foreign + "\",\"BudgetName\":\"foreign\"}")
                .statusCode(400).body("__type", equalTo("AccessDeniedException"));
        jsonAs(caller, "AWSBudgetServiceGateway.ListTagsForResource",
                "{\"ResourceARN\":\"arn:aws:budgets::" + foreign + ":budget/foreign\"}")
                .statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void missingBudgetReturnsNotFound() {
        json("AWSBudgetServiceGateway.DescribeBudget", "{\"AccountId\":\"000000000000\",\"BudgetName\":\"missing\"}")
                .statusCode(400).body("__type", equalTo("NotFoundException"));
    }

    private static io.restassured.response.ValidatableResponse json(String target, String body) {
        return given().contentType(TYPE).header("Authorization", AUTH).header("X-Amz-Target", target).body(body).post("/").then();
    }

    private static io.restassured.response.ValidatableResponse jsonAs(String accountId, String target, String body) {
        String auth = "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260904/us-east-1/budgets/aws4_request";
        return given().contentType(TYPE).header("Authorization", auth).header("X-Amz-Target", target).body(body).post("/").then();
    }
}
