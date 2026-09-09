package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CreateDomainConfiguration hands a customer-managed domain name to the TLS certificate manager
 * on the real wire path. A configuration without a domain name describes the account's default
 * endpoint, which Floci already serves, so nothing is registered for it.
 */
@QuarkusTest
class IotDomainConfigurationEnsureHostIntegrationTest {

    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";

    @InjectMock
    TlsCertificateManager certificateManager;

    @Test
    void customerManagedDomainRegistersTheHostnameForTls() {
        String domain = "iot-" + System.nanoTime() + ".dev.localhost.floci.io";

        given()
            .contentType("application/json")
            .body("""
                {
                  "domainName": "%s",
                  "serverCertificateArns": ["%s"]
                }
                """.formatted(domain, CERTIFICATE_ARN))
        .when()
            .post("/domainConfigurations/ensure-host-" + System.nanoTime())
        .then()
            .statusCode(200);

        verify(certificateManager).ensureHost(domain);
    }

    @Test
    void endpointTypeConfigurationWithoutADomainNameRegistersNothing() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/domainConfigurations/ensure-host-endpoint-" + System.nanoTime())
        .then()
            .statusCode(200);

        verify(certificateManager, never()).ensureHost(anyString());
    }

    @Test
    void rejectedCreateRegistersNothing() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "domainName": "no-cert-%s.dev.localhost.floci.io"
                }
                """.formatted(System.nanoTime()))
        .when()
            .post("/domainConfigurations/ensure-host-no-cert-" + System.nanoTime())
        .then()
            .statusCode(400);

        verify(certificateManager, never()).ensureHost(anyString());
    }
}
