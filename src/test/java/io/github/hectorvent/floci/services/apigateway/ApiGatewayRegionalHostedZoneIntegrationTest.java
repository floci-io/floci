package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The hosted zone both custom domain APIs put on the wire for a region whose General Reference row
 * is not the default region's: {@code regionalHostedZoneId} on REST, {@code hostedZoneId} on HTTP.
 */
@QuarkusTest
class ApiGatewayRegionalHostedZoneIntegrationTest {

    private static final String REGION = "eu-west-1";
    private static final String ZONE = "ZLY8HYME6SFDD";
    private static final String AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=000000000000/20261103/" + REGION + "/apigateway/aws4_request";

    @Test
    void restCreateDomainNameReportsThePublishedZone() {
        String domain = "v1.regional-zone-it.example.com";
        given()
            .header("Authorization", AUTHORIZATION)
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"%s","endpointConfiguration":{"types":["REGIONAL"]}}
                    """.formatted(domain))
        .when()
            .post("/domainnames")
        .then()
            .statusCode(201)
            .body("regionalHostedZoneId", equalTo(ZONE));

        given().header("Authorization", AUTHORIZATION)
        .when()
            .delete("/domainnames/" + domain)
        .then()
            .statusCode(202);
    }

    @Test
    void httpCreateDomainNameReportsThePublishedZone() {
        String domain = "v2.regional-zone-it.example.com";
        given()
            .header("Authorization", AUTHORIZATION)
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"%s","domainNameConfigurations":[{"endpointType":"REGIONAL"}]}
                    """.formatted(domain))
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(201)
            .body("domainNameConfigurations[0].hostedZoneId", equalTo(ZONE));

        given().header("Authorization", AUTHORIZATION)
        .when()
            .delete("/v2/domainnames/" + domain)
        .then()
            .statusCode(204);
    }
}
