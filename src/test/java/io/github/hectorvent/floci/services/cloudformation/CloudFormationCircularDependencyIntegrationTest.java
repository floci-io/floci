package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationCircularDependencyIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_rejectsResourcesThatDependOnEachOther() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cycle-" + suffix;
        String firstQueueName = "cycle-first-" + suffix;
        String secondQueueName = "cycle-second-" + suffix;

        String template = """
                {
                  "Resources": {
                    "FirstQueue": {
                      "Type": "AWS::SQS::Queue",
                      "DependsOn": "SecondQueue",
                      "Properties": { "QueueName": "%s" }
                    },
                    "SecondQueue": {
                      "Type": "AWS::SQS::Queue",
                      "DependsOn": "FirstQueue",
                      "Properties": { "QueueName": "%s" }
                    }
                  }
                }
                """.formatted(firstQueueName, secondQueueName);

        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("Circular dependency between resources"))
                .body("ErrorResponse.Error.Message", containsString("FirstQueue"))
                .body("ErrorResponse.Error.Message", containsString("SecondQueue"));

            assertQueueAbsent(firstQueueName);
            assertQueueAbsent(secondQueueName);
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
                .post("/");
        }
    }

    @Test
    void createStack_namesOnlyTheResourcesInTheCycle() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-cycle-names-" + suffix;

        // Downstream depends on the cycle but is not part of it.
        String template = """
                {
                  "Resources": {
                    "Downstream": { "Type": "AWS::SQS::Queue", "DependsOn": "FirstQueue" },
                    "FirstQueue": { "Type": "AWS::SQS::Queue", "DependsOn": "SecondQueue" },
                    "SecondQueue": { "Type": "AWS::SQS::Queue", "DependsOn": "FirstQueue" }
                  }
                }
                """;

        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("FirstQueue"))
                .body("ErrorResponse.Error.Message", containsString("SecondQueue"))
                .body("ErrorResponse.Error.Message", not(containsString("Downstream")));
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
                .post("/");
        }
    }

    private static void assertQueueAbsent(String queueName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
    }
}
