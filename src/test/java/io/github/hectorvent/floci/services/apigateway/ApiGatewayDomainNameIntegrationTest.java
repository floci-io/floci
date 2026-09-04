package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code CreateDomainName} keeps and {@code GetDomainName} reports for a REST custom domain:
 * the regional certificate, the endpoint configuration, the ARN, the tags, and for an
 * edge-optimized domain the CloudFront distribution a DNS alias would point at. Tags are managed
 * through the tag API on the domain's ARN, as on AWS.
 */
@QuarkusTest
class ApiGatewayDomainNameIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";

    @Inject
    ApiGatewayService service;

    @Test
    void createKeepsTheRegionalCertificateAndReportsTheEndpointConfiguration() {
        String domain = "regional.apigw-domain-it.example.com";
        createDomain("""
                {"domainName":"%s",
                 "regionalCertificateArn":"%s",
                 "regionalCertificateName":"regional-cert",
                 "endpointConfiguration":{"types":["REGIONAL"]}}
                """.formatted(domain, CERTIFICATE_ARN))
            .body("regionalCertificateArn", equalTo(CERTIFICATE_ARN))
            .body("regionalCertificateName", equalTo("regional-cert"));

        given().when().get("/domainnames/" + domain).then()
            .statusCode(200)
            .body("domainNameArn", equalTo("arn:aws:apigateway:us-east-1::/domainnames/" + domain))
            .body("regionalCertificateArn", equalTo(CERTIFICATE_ARN))
            .body("regionalCertificateName", equalTo("regional-cert"))
            .body("endpointConfiguration.types[0]", equalTo("REGIONAL"))
            .body("regionalDomainName", equalTo(domain + ".regional.local"))
            .body("distributionDomainName", nullValue())
            .body("distributionHostedZoneId", nullValue());

        deleteDomain(domain);
    }

    @Test
    void edgeDomainReportsTheDistributionADnsAliasPointsAt() {
        String domain = "edge.apigw-domain-it.example.com";
        createDomain("""
                {"domainName":"%s",
                 "certificateArn":"%s",
                 "endpointConfiguration":{"types":["EDGE"]}}
                """.formatted(domain, CERTIFICATE_ARN))
            .body("endpointConfiguration.types[0]", equalTo("EDGE"))
            .body("distributionDomainName", endsWith(".cloudfront.net"))
            .body("distributionHostedZoneId", equalTo("Z2FDTNDATAQYW2"));

        deleteDomain(domain);
    }

    @Test
    void endpointTypeCanBeMovedFromEdgeToRegional() {
        String domain = "endpoint-type.apigw-domain-it.example.com";
        createDomain("""
                {"domainName":"%s","certificateArn":"%s","endpointConfiguration":{"types":["EDGE"]}}
                """.formatted(domain, CERTIFICATE_ARN));

        // The patch path names the type the domain has now, the value the type it should get. A
        // regional domain has no distribution, so the move drops the CloudFront fields.
        given().contentType(ContentType.JSON)
            .body("""
                {"patchOperations":[{"op":"replace","path":"/endpointConfiguration/types/EDGE","value":"REGIONAL"}]}
                """)
        .when().patch("/domainnames/" + domain).then()
            .statusCode(200)
            .body("endpointConfiguration.types[0]", equalTo("REGIONAL"))
            .body("distributionDomainName", nullValue())
            .body("distributionHostedZoneId", nullValue());

        deleteDomain(domain);
    }

    @Test
    void endpointTypeCanBeMovedFromRegionalToEdge() {
        String domain = "edge-migration.apigw-domain-it.example.com";
        createDomain("""
                {"domainName":"%s","regionalCertificateArn":"%s","endpointConfiguration":{"types":["REGIONAL"]}}
                """.formatted(domain, CERTIFICATE_ARN))
            .body("distributionDomainName", nullValue());

        // Moving to EDGE puts a distribution in front of the domain, as on AWS.
        given().contentType(ContentType.JSON)
            .body("""
                {"patchOperations":[{"op":"replace","path":"/endpointConfiguration/types/REGIONAL","value":"EDGE"}]}
                """)
        .when().patch("/domainnames/" + domain).then()
            .statusCode(200)
            .body("endpointConfiguration.types[0]", equalTo("EDGE"))
            .body("distributionDomainName", endsWith(".cloudfront.net"))
            .body("distributionHostedZoneId", equalTo("Z2FDTNDATAQYW2"));

        given().when().get("/domainnames/" + domain).then()
            .statusCode(200)
            .body("distributionDomainName", endsWith(".cloudfront.net"));

        deleteDomain(domain);
    }

    @Test
    void tagsAreReportedAndManagedThroughTheTagApi() {
        String domain = "tags.apigw-domain-it.example.com";
        String arn = "arn:aws:apigateway:us-east-1::/domainnames/" + domain;
        createDomain("""
                {"domainName":"%s","regionalCertificateArn":"%s","tags":{"env":"prod"}}
                """.formatted(domain, CERTIFICATE_ARN))
            .body("tags.env", equalTo("prod"));

        given().pathParam("arn", arn).contentType(ContentType.JSON)
            .body("{\"tags\":{\"team\":\"api\"}}")
        .when().put("/tags/{arn}").then().statusCode(204);

        given().pathParam("arn", arn).when().get("/tags/{arn}").then()
            .statusCode(200)
            .body("tags.env", equalTo("prod"))
            .body("tags.team", equalTo("api"));

        given().pathParam("arn", arn).queryParam("tagKeys", "env")
        .when().delete("/tags/{arn}").then().statusCode(anyOf(is(200), is(204)));

        given().when().get("/domainnames/" + domain).then()
            .statusCode(200)
            .body("tags.env", nullValue())
            .body("tags.team", equalTo("api"));

        deleteDomain(domain);
    }

    @Test
    void concurrentCreatesOfOneDomainNameAdmitExactlyOne() throws Exception {
        String domain = "race.apigw-domain-it.example.com";
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.createDomainName(REGION, Map.of("domainName", domain));
                        created.incrementAndGet();
                    } catch (AwsException e) {
                        assertEquals("BadRequestException", e.getErrorCode());
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, created.get(), "domain names are unique, so exactly one caller may create it");
        assertEquals(callers - 1, rejected.get());
        service.deleteDomainName(REGION, domain);
    }

    private static io.restassured.response.ValidatableResponse createDomain(String body) {
        return given().contentType(ContentType.JSON).body(body)
            .when().post("/domainnames").then().statusCode(201);
    }

    private static void deleteDomain(String domain) {
        given().when().delete("/domainnames/" + domain).then().statusCode(202);
    }
}
