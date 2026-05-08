package io.github.hectorvent.floci.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EmulatorInfoControllerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Core services that must always be present and running.
    // New services can be added to Floci without updating this list —
    // the test verifies all known services are present, not an exact set.
    private static final List<String> CORE_SERVICES = List.of(
            "ssm", "sqs", "s3", "dynamodb", "sns", "lambda",
            "apigateway", "iam", "kafka", "elasticache", "rds",
            "events", "scheduler", "logs", "monitoring", "secretsmanager",
            "apigatewayv2", "kinesis", "kms", "cognito-idp", "states",
            "cloudformation", "acm", "athena", "glue", "firehose",
            "email", "es", "ec2", "ecs", "appconfig", "appconfigdata",
            "ecr", "tagging", "bedrock-runtime", "eks", "pipes",
            "elasticloadbalancing", "codebuild", "codedeploy", "autoscaling",
            "backup", "route53", "transfer"
    );

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/_localstack/health"})
    void health_returnsSameResponseOnBothPaths(String path) throws Exception {
        String body = given()
            .when().get(path)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .extract().body().asString();

        JsonNode tree = MAPPER.readTree(body);
        assertEquals("community", tree.get("edition").asText());
        assertEquals("floci-always-free", tree.get("original_edition").asText());
        assertEquals("dev", tree.get("version").asText());

        JsonNode services = tree.get("services");
        assertNotNull(services, "services field must be present");
        for (String service : CORE_SERVICES) {
            assertEquals("running", services.path(service).asText(),
                    "Service '" + service + "' must be running");
        }
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
