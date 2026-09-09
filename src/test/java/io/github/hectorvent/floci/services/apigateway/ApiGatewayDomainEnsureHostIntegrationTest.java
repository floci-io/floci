package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CreateDomainName hands the new hostname to the TLS certificate manager on the real wire path,
 * so a client that verifies the domain's name over HTTPS is served without a restart. The
 * manager is mocked: what it does with the name is its own test's business.
 */
@QuarkusTest
class ApiGatewayDomainEnsureHostIntegrationTest {

    @InjectMock
    TlsCertificateManager certificateManager;

    @Test
    void regionalDomainRegistersTheHostnameForTls() {
        String domain = "api-" + System.nanoTime() + ".dev.localhost.floci.io";

        given()
                .contentType(ContentType.JSON)
                .body("{\"domainName\":\"" + domain + "\","
                        + "\"regionalCertificateArn\":\"arn:aws:acm:us-east-1:000000000000:certificate/abc\","
                        + "\"endpointConfiguration\":{\"types\":[\"REGIONAL\"]}}")
                .when().post("/domainnames")
                .then()
                .statusCode(201);

        verify(certificateManager).ensureHost(domain);
    }

    @Test
    void edgeDomainRegistersTheHostnameForTls() {
        String domain = "edge-" + System.nanoTime() + ".dev.localhost.floci.io";

        given()
                .contentType(ContentType.JSON)
                .body("{\"domainName\":\"" + domain + "\","
                        + "\"certificateArn\":\"arn:aws:acm:us-east-1:000000000000:certificate/abc\","
                        + "\"endpointConfiguration\":{\"types\":[\"EDGE\"]}}")
                .when().post("/domainnames")
                .then()
                .statusCode(201);

        verify(certificateManager).ensureHost(domain);
    }

    @Test
    void rejectedCreateRegistersNothing() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"domainName\":\"private-" + System.nanoTime() + ".dev.localhost.floci.io\","
                        + "\"endpointConfiguration\":{\"types\":[\"PRIVATE\"]}}")
                .when().post("/domainnames")
                .then()
                .statusCode(400);

        verify(certificateManager, never()).ensureHost(anyString());
    }
}
