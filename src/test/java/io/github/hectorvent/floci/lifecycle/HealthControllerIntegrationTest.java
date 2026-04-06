package io.github.hectorvent.floci.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class HealthControllerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EXPECTED_HEALTH_JSON = """
            {
              "services": {
                "ssm": "running",
                "sqs": "running",
                "s3": "running",
                "dynamodb": "running",
                "sns": "running",
                "lambda": "running",
                "apigateway": "running",
                "iam": "running",
                "elasticache": "running",
                "rds": "running",
                "events": "running",
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
                "email": "running",
                "es": "running",
                "ec2": "running",
                "ecs": "running"
              },
              "edition": "floci-always-free",
              "version": "dev"
            }
            """;

    @Test
    void healthEndpoint_returnsExpectedJson() throws Exception {
        String body = given()
            .when()
                .get("/_floci/health")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .extract().body().asString();

        assertEquals(MAPPER.readTree(EXPECTED_HEALTH_JSON), MAPPER.readTree(body));
    }

    @Test
    void healthEndpoint_localstackCompatPath() throws Exception {
        String body = given()
            .when()
                .get("/_localstack/health")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .extract().body().asString();

        assertEquals(MAPPER.readTree(EXPECTED_HEALTH_JSON), MAPPER.readTree(body));
    }
}
