package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code floci.services.iot.endpoint-address} set, DescribeEndpoint answers with that value
 * for every endpoint type, the way AWS returns a bare hostname that devices complete with their
 * own port. {@link IotIntegrationTest} and {@link IotEndpointHostnameIntegrationTest} keep
 * guarding the unset case, the host and port of the base URL.
 */
@QuarkusTest
@TestProfile(IotDescribeEndpointOverrideIntegrationTest.Profile.class)
class IotDescribeEndpointOverrideIntegrationTest {

    static final String ENDPOINT_ADDRESS = "iot.example.localhost.floci.io";

    @Test
    void omittedEndpointTypeReturnsTheConfiguredAddress() {
        given()
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo(ENDPOINT_ADDRESS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iot:Data-ATS", "iot:Data", "iot:Jobs", "iot:CredentialProvider"})
    void everyEndpointTypeReturnsTheConfiguredAddressVerbatim(String endpointType) {
        given()
            .queryParam("endpointType", endpointType)
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo(ENDPOINT_ADDRESS));
    }

    @Test
    void unsupportedEndpointTypeIsStillRejected() {
        given()
            .queryParam("endpointType", "iot:Nonsense")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iot:Data-ATS", "iot:Data", "iot:Jobs", "iot:CredentialProvider"})
    void awsManagedDomainConfigurationsReportTheConfiguredAddress(String name) {
        given()
        .when()
            .get("/domainConfigurations/" + name)
        .then()
            .statusCode(200)
            .body("domainType", equalTo("AWS_MANAGED"))
            .body("domainName", equalTo(ENDPOINT_ADDRESS));
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iot.endpoint-address", ENDPOINT_ADDRESS);
        }
    }
}
