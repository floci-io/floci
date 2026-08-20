package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationListStacksIntegrationTest {

    @Test
    void listStacksReturnsOnlyRequestedStatuses() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String completeStack = "list-stacks-complete-" + suffix;
        String rolledBackStack = "list-stacks-rollback-" + suffix;

        createStack(completeStack, """
                {
                  "Resources": {
                    "Parameter": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {
                        "Name": "/list-stacks/%s",
                        "Type": "String",
                        "Value": "complete"
                      }
                    }
                  }
                }
                """.formatted(suffix));
        createStack(rolledBackStack, """
                {
                  "Resources": {
                    "InvalidSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {
                        "Name": "list-stacks-invalid-%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": { "PasswordLength": 32 }
                      }
                    }
                  }
                }
                """.formatted(suffix));

        String response = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListStacks")
            .formParam("StackStatusFilter.member.1", "ROLLBACK_COMPLETE")
            .formParam("StackStatusFilter.member.2", "DELETE_FAILED")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(response, containsString("<StackName>" + rolledBackStack + "</StackName>"));
        assertThat(response, containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"));
        assertThat(response, not(containsString("<StackName>" + completeStack + "</StackName>")));
    }

    private static void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
