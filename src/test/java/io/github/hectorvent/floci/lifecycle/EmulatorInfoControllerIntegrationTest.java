package io.github.hectorvent.floci.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EmulatorInfoControllerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EXPECTED_SERVICES_JSON = """
            {
              "ssm": "running",
              "sqs": "running",
              "s3": "running",
              "dynamodb": "running",
              "sns": "running",
              "lambda": "running",
              "apigateway": "running",
              "iam": "running",
              "kafka": "running",
              "elasticache": "running",
              "rds": "running",
              "events": "running",
              "scheduler": "running",
              "logs": "running",
              "monitoring": "running",
              "secretsmanager": "running",
              "apigatewayv2": "running",
              "kinesis": "running",
              "kms": "running",
              "cognito-idp": "running",
              "states": "running",
              "cloudformation": "running",
              "acm": "running",
              "athena": "running",
              "glue": "running",
              "firehose": "running",
              "email": "running",
              "es": "running",
              "ec2": "running",
              "ecs": "running",
              "appconfig": "running",
              "appconfigdata": "running",
              "ecr": "running",
              "tagging": "running",
              "bedrock-runtime": "running",
              "eks": "running",
              "pipes": "running",
              "elasticloadbalancing": "running",
              "codebuild": "running",
              "codedeploy": "running",
              "autoscaling": "running",
              "backup": "running",
              "route53": "running",
              "transfer": "running"
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/_localstack/health"})
    void health_returnsSameResponseOnBothPaths(String path) throws Exception {
        String body = given()
            .when().get(path)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .extract().body().asString();

        var tree = MAPPER.readTree(body);
        assertEquals("community", tree.get("edition").asText());
        assertEquals("floci-always-free", tree.get("original_edition").asText());
        assertEquals("dev", tree.get("version").asText());
        assertEquals(MAPPER.readTree(EXPECTED_SERVICES_JSON), tree.get("services"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/init", "/_localstack/init"})
    void init_returnsLifecycleStateOnBothPaths(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("completed.boot", equalTo(true))
                .body("completed.start", equalTo(true))
                .body("completed.ready", equalTo(true))
                .body("completed.shutdown", equalTo(false))
                .body("scripts.boot", hasSize(0))
                .body("scripts.start", hasSize(0))
                .body("scripts.ready", hasSize(0))
                .body("scripts.shutdown", hasSize(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/info", "/_localstack/info"})
    void info_returnsVersionAndEditionOnBothPaths(String path) {
        given()
            .when().get(path)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("edition", equalTo("community"))
                .body("version", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/diagnose", "/_localstack/diagnose"})
    void diagnose_returns200OnBothPaths(String path) {
        given().when().get(path).then().statusCode(200).contentType("application/json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/config", "/_localstack/config"})
    void config_returns200OnBothPaths(String path) {
        given().when().get(path).then().statusCode(200).contentType("application/json");
    }
}
