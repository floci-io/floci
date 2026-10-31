package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.GetAccountSettingResponse;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;
import software.amazon.awssdk.services.ecr.model.PutAccountSettingResponse;
import software.amazon.awssdk.services.ecr.model.ValidationException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EcrAccountSettingsIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(741682900000L);
    private static final String REGION = "eu-west-2";
    private static final Map<String, String> DEFAULTS = Map.of(
            "BASIC_SCAN_TYPE_VERSION", "AWS_NATIVE", "REGISTRY_POLICY_SCOPE", "V2", "BLOB_MOUNTING", "DISABLED");
    private String account;
    private EcrClient client;

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        account = String.valueOf(ACCOUNTS.incrementAndGet());
        client = AwsTestClients.create(EcrClient.builder(), account, Region.of(REGION));
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void defaultsAreReturnedByName() {
        DEFAULTS.forEach((name, value) -> {
            GetAccountSettingResponse response = client.getAccountSetting(request -> request.name(name));
            assertEquals(name, response.name());
            assertEquals(value, response.value());
        });
    }

    @ParameterizedTest
    @CsvSource({"BASIC_SCAN_TYPE_VERSION,AWS_NATIVE", "REGISTRY_POLICY_SCOPE,V2",
            "BLOB_MOUNTING,ENABLED", "BLOB_MOUNTING,DISABLED"})
    void validValuesRoundTrip(String name, String value) {
        PutAccountSettingResponse response = client.putAccountSetting(request -> request.name(name).value(value));
        assertEquals(name, response.name());
        assertEquals(value, response.value());
        assertEquals(value, client.getAccountSetting(request -> request.name(name)).value());
    }

    @Test
    void accountAndRegionAreIsolated() {
        client.putAccountSetting(request -> request.name("BLOB_MOUNTING").value("ENABLED"));
        try (EcrClient other = AwsTestClients.create(EcrClient.builder(), "741682999999", Region.of(REGION));
             EcrClient region = AwsTestClients.create(EcrClient.builder(), account, Region.US_EAST_1)) {
            assertEquals("DISABLED", other.getAccountSetting(request -> request.name("BLOB_MOUNTING")).value());
            assertEquals("DISABLED", region.getAccountSetting(request -> request.name("BLOB_MOUNTING")).value());
        }
        assertEquals("ENABLED", client.getAccountSetting(request -> request.name("BLOB_MOUNTING")).value());
        assertEquals("AWS_NATIVE", client.getAccountSetting(request -> request.name("BASIC_SCAN_TYPE_VERSION")).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "blob_mounting"})
    void unsupportedNamesAreValidationErrors(String name) {
        assertThrows(ValidationException.class, () -> client.getAccountSetting(request -> request.name(name)));
        assertThrows(ValidationException.class, () -> client.putAccountSetting(request -> request.name(name).value("ENABLED")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {""})
    void missingAndEmptyNamesUseTheRequestModelError(String name) {
        assertThrows(InvalidParameterException.class, () -> client.getAccountSetting(request -> request.name(name)));
        assertThrows(InvalidParameterException.class, () -> client.putAccountSetting(request -> request.name(name).value("ENABLED")));
    }

    @Test
    void namesLongerThanTheModelLimitAreRejectedBeforeSettingLookup() {
        String name = "x".repeat(65);
        assertThrows(InvalidParameterException.class, () -> client.getAccountSetting(request -> request.name(name)));
        assertThrows(InvalidParameterException.class, () -> client.putAccountSetting(request -> request.name(name).value("ENABLED")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":1}", "{\"name\":[]}", "{\"name\":{}}"})
    void getSettingUsesModelValidation(String body) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.GetAccountSetting")
                .body(body).post("/").then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @ParameterizedTest
    @CsvSource({"BASIC_SCAN_TYPE_VERSION,CLAIR", "REGISTRY_POLICY_SCOPE,V1", "BLOB_MOUNTING,enabled",
            "BLOB_MOUNTING,AWS_NATIVE", "REGISTRY_POLICY_SCOPE,ENABLED"})
    void invalidValuesLeaveStoredSettingsUnchanged(String name, String value) {
        client.putAccountSetting(request -> request.name("BLOB_MOUNTING").value("ENABLED"));
        assertThrows(ValidationException.class, () -> client.putAccountSetting(request -> request.name(name).value(value)));
        assertEquals("ENABLED", client.getAccountSetting(request -> request.name("BLOB_MOUNTING")).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":1}", "{\"name\":[]}", "{\"name\":{}}",
            "{\"name\":\"BLOB_MOUNTING\"}", "{\"name\":\"BLOB_MOUNTING\",\"value\":null}",
            "{\"name\":\"BLOB_MOUNTING\",\"value\":true}", "{\"name\":\"BLOB_MOUNTING\",\"value\":{}}"})
    void missingOrNonStringInputsAreRejected(String body) {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.PutAccountSetting")
                .body(body).post("/").then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }
}
