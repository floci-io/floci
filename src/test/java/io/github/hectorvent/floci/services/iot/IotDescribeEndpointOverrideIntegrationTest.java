package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.IotSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.hectorvent.floci.testing.IotSettingsProfile.ENDPOINT_ADDRESS;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code floci.services.iot.endpoint-address} set, DescribeEndpoint answers with that value
 * for every endpoint type, the way AWS returns a bare hostname that devices complete with their
 * own port. {@link IotIntegrationTest} and {@link IotEndpointHostnameIntegrationTest} keep
 * guarding the unset case, the host and port of the base URL.
 *
 * <p>The address comes from {@link IotSettingsProfile}, which this class shares with the other IoT
 * class that changes a default.
 */
@QuarkusTest
@TestProfile(IotSettingsProfile.class)
class IotDescribeEndpointOverrideIntegrationTest {

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
}
