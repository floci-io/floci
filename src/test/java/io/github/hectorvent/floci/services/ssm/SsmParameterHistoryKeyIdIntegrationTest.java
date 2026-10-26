package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterHistory;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SsmParameterHistoryKeyIdIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @Inject
    SsmService ssmService;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getParameterHistoryReturnsEachSecureVersionKeyId() {
        String name = "/history-key-id-" + UUID.randomUUID();
        try {
            put(name, "first", "alias/first-key", false);
            put(name, "second", "alias/second-key", true);
            put(name, "third", null, true);

            JsonPath history = given()
                    .header("X-Amz-Target", "AmazonSSM.GetParameterHistory")
                    .contentType(CONTENT_TYPE)
                    .body("{\"Name\":\"" + name + "\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath();

            assertEquals("alias/first-key", history.getString("Parameters[0].KeyId"));
            assertEquals("alias/second-key", history.getString("Parameters[1].KeyId"));
            assertEquals("alias/aws/ssm", history.getString("Parameters[2].KeyId"));

            try (SsmClient client = SsmClient.builder()
                    .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .build()) {
                List<ParameterHistory> versions = client.getParameterHistory(request -> request.name(name)).parameters();
                assertEquals("alias/first-key", versions.get(0).keyId());
                assertEquals("alias/second-key", versions.get(1).keyId());
                assertEquals("alias/aws/ssm", versions.get(2).keyId());
            }
        } finally {
            delete(name);
        }
    }

    @Test
    void legacyHistoryWithoutSavedKeyDoesNotGuessDefaultKey() {
        String name = "/legacy-history-key-id-" + UUID.randomUUID();
        try {
            put(name, "old", "alias/old-custom-key", false);
            ssmService.getParameterHistory(name, "us-east-1").getFirst().setKeyId(null);

            JsonPath history = given()
                    .header("X-Amz-Target", "AmazonSSM.GetParameterHistory")
                    .contentType(CONTENT_TYPE)
                    .body("{\"Name\":\"" + name + "\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath();
            assertNull(history.getString("Parameters[0].KeyId"));
        } finally {
            delete(name);
        }
    }

    private void delete(String name) {
        given()
                .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
                .contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\"}")
                .when().post("/");
    }

    private void put(String name, String value, String keyId, boolean overwrite) {
        String keyIdField = keyId == null ? "" : ",\"KeyId\":\"" + keyId + "\"";
        given()
                .header("X-Amz-Target", "AmazonSSM.PutParameter")
                .contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\",\"Value\":\"" + value
                        + "\",\"Type\":\"SecureString\"" + keyIdField + ",\"Overwrite\":" + overwrite + "}")
                .when().post("/")
                .then().statusCode(200);
    }
}
