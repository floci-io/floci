package io.github.hectorvent.floci.services.dynamodb.backend;

import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The emulator lifecycle drives only the selected DynamoDB backend, here one that refuses resets. */
@QuarkusTest
@TestProfile(DynamoDbBackendResetIntegrationTest.RefusingBackendProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbBackendResetIntegrationTest {

    public static final class RefusingBackendProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(RefusingDynamoDbBackendLifecycle.class);
        }
    }

    private static final String PARAMETER = "/dynamodb-backend-reset/survives-a-refused-reset";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void startupInitializesNoNativeDatabaseState() {
        assertFalse(Arc.container().getActiveContext(ApplicationScoped.class).getState().getContextualInstances()
                .keySet().stream().anyMatch(bean -> bean.getBeanClass() == DynamoDbService.class));
    }

    @Test
    @Order(2)
    void aRefusedResetClearsNothing() {
        ssm("PutParameter", "{\"Name\": \"" + PARAMETER + "\", \"Value\": \"kept\", \"Type\": \"String\"}")
            .statusCode(200);

        given().when().post("/_floci/state/reset").then().statusCode(409);

        ssm("GetParameter", "{\"Name\": \"" + PARAMETER + "\"}")
            .statusCode(200)
            .body("Parameter.Value", equalTo("kept"));
    }

    private static ValidatableResponse ssm(String action, String body) {
        return given()
            .header("X-Amz-Target", "AmazonSSM." + action)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when()
            .post("/")
        .then();
    }
}
