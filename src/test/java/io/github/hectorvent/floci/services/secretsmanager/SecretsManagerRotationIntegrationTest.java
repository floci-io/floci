package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@QuarkusTest
public class SecretsManagerRotationIntegrationTest {

    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGION = "us-east-1";

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void testRotateSecretTriggersLambda() {
        // Mock lambda response to avoid errors
        InvokeResult successResult = new InvokeResult();
        successResult.setStatusCode(200);
        Mockito.when(lambdaService.invoke(any(), any(), any(), any())).thenReturn(successResult);

        // 1. Create a secret
        String secretName = "rotation-test-secret";
        String lambdaArn = "arn:aws:lambda:us-east-1:000000000000:function:my-rotation-lambda";

        String createSecretResponse = given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(SM_CONTENT_TYPE)
            .body("{" +
                "\"Name\": \"" + secretName + "\"," +
                "\"SecretString\": \"initial-value\"" +
                "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        // extract ARN if needed, but we can just refer by Name

        // 2. Rotate the secret
        given()
            .header("X-Amz-Target", "secretsmanager.RotateSecret")
            .contentType(SM_CONTENT_TYPE)
            .body("{" +
                "\"SecretId\": \"" + secretName + "\"," +
                "\"RotationLambdaARN\": \"" + lambdaArn + "\"" +
                "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ARN", notNullValue())
            .body("VersionId", notNullValue());

        // 3. Verify that lambda was invoked 4 times for the rotation steps (timeout allows async execution to finish)
        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);

        Mockito.verify(lambdaService, Mockito.timeout(5000).times(4))
                .invoke(eq(REGION), eq(lambdaArn), payloadCaptor.capture(), eq(InvocationType.RequestResponse));

        // 4. Verify payloads contain the required steps
        boolean hasCreate = false;
        boolean hasSet = false;
        boolean hasTest = false;
        boolean hasFinish = false;

        for (byte[] payloadBytes : payloadCaptor.getAllValues()) {
            String payloadStr = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (payloadStr.contains("\"Step\":\"createSecret\"")) hasCreate = true;
            if (payloadStr.contains("\"Step\":\"setSecret\"")) hasSet = true;
            if (payloadStr.contains("\"Step\":\"testSecret\"")) hasTest = true;
            if (payloadStr.contains("\"Step\":\"finishSecret\"")) hasFinish = true;
        }

        assertTrue(hasCreate, "Should invoke createSecret");
        assertTrue(hasSet, "Should invoke setSecret");
        assertTrue(hasTest, "Should invoke testSecret");
        assertTrue(hasFinish, "Should invoke finishSecret");
    }
}
