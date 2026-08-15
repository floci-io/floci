package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SageMakerAlgorithmIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TRAINING_SPECIFICATION = """
        {
          "TrainingImage": "123456789012.dkr.ecr.us-east-1.amazonaws.com/algo:latest",
          "SupportedTrainingInstanceTypes": ["ml.m5.large"],
          "TrainingChannels": [
            {
              "Name": "train",
              "SupportedContentTypes": ["text/csv"],
              "SupportedInputModes": ["File"]
            }
          ]
        }
        """;

    private static String algorithmArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createAlgorithm() {
        String response = given()
            .header("X-Amz-Target", "SageMaker.CreateAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "AlgorithmName": "integration-algorithm",
                  "AlgorithmDescription": "an integration test algorithm",
                  "TrainingSpecification": %s,
                  "Tags": [{"Key": "team", "Value": "ml"}]
                }
                """.formatted(TRAINING_SPECIFICATION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AlgorithmArn", containsString(":sagemaker:"))
            .body("AlgorithmArn", containsString(":algorithm/integration-algorithm"))
            .extract().path("AlgorithmArn");

        algorithmArn = response;
    }

    @Test
    @Order(2)
    void describeAlgorithmReturnsTerminalStatusAndEchoesRequest() {
        given()
            .header("X-Amz-Target", "SageMaker.DescribeAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("{ \"AlgorithmName\": \"integration-algorithm\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AlgorithmName", equalTo("integration-algorithm"))
            .body("AlgorithmArn", equalTo(algorithmArn))
            .body("AlgorithmDescription", equalTo("an integration test algorithm"))
            .body("AlgorithmStatus", equalTo("Completed"))
            .body("CreationTime", instanceOf(Number.class))
            .body("TrainingSpecification.TrainingImage",
                    equalTo("123456789012.dkr.ecr.us-east-1.amazonaws.com/algo:latest"))
            .body("TrainingSpecification.TrainingChannels[0].Name", equalTo("train"))
            .body("AlgorithmStatusDetails.ValidationStatuses", empty())
            .body("AlgorithmStatusDetails.ImageScanStatuses", empty());
    }

    @Test
    @Order(3)
    void describeAlgorithmAcceptsArn() {
        given()
            .header("X-Amz-Target", "SageMaker.DescribeAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("{ \"AlgorithmName\": \"" + algorithmArn + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AlgorithmName", equalTo("integration-algorithm"));
    }

    @Test
    @Order(4)
    void describeMissingAlgorithmReturnsValidationExceptionDoesNotExist() {
        // SageMaker's own model declares no typed error for DescribeAlgorithm, so the real
        // terraform-provider-aws source (findAlgorithmByName in algorithm.go) only recognizes
        // a missing algorithm via ValidationException whose message contains "does not exist" —
        // any other code is invisible to its waiter and hangs the delete.
        given()
            .header("X-Amz-Target", "SageMaker.DescribeAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("{ \"AlgorithmName\": \"does-not-exist\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("does not exist"));
    }

    @Test
    @Order(5)
    void listAlgorithmsIncludesCreatedAlgorithm() {
        given()
            .header("X-Amz-Target", "SageMaker.ListAlgorithms")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AlgorithmSummaryList.AlgorithmName", hasItem("integration-algorithm"));
    }

    @Test
    @Order(6)
    void listTagsReturnsTagsSetOnCreate() {
        given()
            .header("X-Amz-Target", "SageMaker.ListTags")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceArn\": \"" + algorithmArn + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("ml"));
    }

    @Test
    @Order(7)
    void addAndDeleteTags() {
        given()
            .header("X-Amz-Target", "SageMaker.AddTags")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceArn\": \"" + algorithmArn + "\", \"Tags\": [{\"Key\": \"env\", \"Value\": \"test\"}] }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("test"));

        given()
            .header("X-Amz-Target", "SageMaker.DeleteTags")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceArn\": \"" + algorithmArn + "\", \"TagKeys\": [\"env\"] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "SageMaker.ListTags")
            .contentType(CONTENT_TYPE)
            .body("{ \"ResourceArn\": \"" + algorithmArn + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("team"))
            .body("Tags.Key", org.hamcrest.Matchers.not(hasItem("env")));
    }

    @Test
    @Order(8)
    void deleteAlgorithm() {
        given()
            .header("X-Amz-Target", "SageMaker.DeleteAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("{ \"AlgorithmName\": \"integration-algorithm\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "SageMaker.DescribeAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("{ \"AlgorithmName\": \"integration-algorithm\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("does not exist"));
    }

    @Test
    @Order(9)
    void createAlgorithmRequiresTrainingSpecification() {
        given()
            .header("X-Amz-Target", "SageMaker.CreateAlgorithm")
            .contentType(CONTENT_TYPE)
            .body("{ \"AlgorithmName\": \"missing-training-spec\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
