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
    void createRejectsAnEndpointTypeOtherThanRegionalOrEdge() {
        // Private custom domains are not emulated, and an unknown type would leave a domain that is
        // neither regional nor edge, so both are refused up front and nothing is stored.
        String domain = "private.apigw-domain-it.example.com";
        for (String type : List.of("PRIVATE", "FOOBAR")) {
            given().contentType(ContentType.JSON)
                .body("""
                    {"domainName":"%s","regionalCertificateArn":"%s","endpointConfiguration":{"types":["%s"]}}
                    """.formatted(domain, CERTIFICATE_ARN, type))
            .when().post("/domainnames").then()
                .statusCode(400)
                .body("message", equalTo("Invalid value for endpoint type: " + type));
        }
        given().when().get("/domainnames/" + domain).then().statusCode(404);
    }

    @Test
    void endpointTypePatchPathMustNameTheCurrentType() {
        String domain = "endpoint-path.apigw-domain-it.example.com";
        createDomain("""
                {"domainName":"%s","regionalCertificateArn":"%s","endpointConfiguration":{"types":["REGIONAL"]}}
                """.formatted(domain, CERTIFICATE_ARN));

        // A path naming a type the domain does not have, or no type at all, is rejected as on AWS,
        // and the domain is left as it was.
        for (String path : List.of("/endpointConfiguration/types/EDGE", "/endpointConfiguration/types/FOO")) {
            given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"" + path + "\",\"value\":\"EDGE\"}]}")
            .when().patch("/domainnames/" + domain).then()
                .statusCode(400)
                .body("message", equalTo("Invalid patch path " + path
                        + ": the path must name the domain's current endpoint type, REGIONAL"));
        }
        given().when().get("/domainnames/" + domain).then()
            .statusCode(200)
            .body("endpointConfiguration.types[0]", equalTo("REGIONAL"))
            .body("distributionDomainName", nullValue());

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

    @Test
    void creatingAMappingOnATakenBasePathIsAConflict() {
        String domain = "conflict.apigw-domain-it.example.com";
        createDomain("""
                {"domainName":"%s","regionalCertificateArn":"%s"}
                """.formatted(domain, CERTIFICATE_ARN));
        String mapping = "{\"basePath\":\"v1\",\"restApiId\":\"abc123def4\",\"stage\":\"prod\"}";
        given().contentType(ContentType.JSON).body(mapping)
            .when().post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);

        // AWS refuses a second mapping on the same base path rather than replacing the first.
        given().contentType(ContentType.JSON).body(mapping)
            .when().post("/domainnames/" + domain + "/basepathmappings").then()
            .statusCode(409)
            .body("message", equalTo("Base path already exists for this domain name"));

        given().when().get("/domainnames/" + domain + "/basepathmappings/v1").then()
            .statusCode(200)
            .body("stage", equalTo("prod"));

        deleteDomain(domain);
    }

    @Test
    void concurrentMappingCreatesOnOneBasePathAdmitExactlyOne() throws Exception {
        String domain = "mapping-race.apigw-domain-it.example.com";
        service.createDomainName(REGION, Map.of("domainName", domain));
        Outcome outcome = race(8, () -> service.createBasePathMapping(REGION, domain,
                Map.of("basePath", "v1", "restApiId", "abc123def4", "stage", "prod")), "ConflictException");

        assertEquals(1, outcome.succeeded(), "one base path holds one mapping, so exactly one caller may create it");
        assertEquals(7, outcome.rejected());
        service.deleteDomainName(REGION, domain);
    }

    @Test
    void concurrentTagWritesOnOneDomainKeepEveryKey() throws Exception {
        String domain = "tag-race.apigw-domain-it.example.com";
        service.createDomainName(REGION, Map.of("domainName", domain));
        AtomicInteger next = new AtomicInteger();
        Outcome outcome = race(8, () -> {
            String key = "key-" + next.getAndIncrement();
            service.tagDomainName(REGION, domain, Map.of(key, "value"));
        }, null);

        assertEquals(8, outcome.succeeded());
        Map<String, String> tags = service.getDomainNameTags(REGION, domain);
        assertEquals(8, tags.size(), "a tag write must not lose a concurrent write: " + tags);
        service.deleteDomainName(REGION, domain);
    }

    private record Outcome(int succeeded, int rejected) {
    }

    /** Runs the call from {@code callers} threads released together; a rejection must carry {@code rejectedCode}. */
    private static Outcome race(int callers, ThrowingRunnable call, String rejectedCode) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        call.run();
                        succeeded.incrementAndGet();
                    } catch (AwsException e) {
                        assertEquals(rejectedCode, e.getErrorCode());
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : calls) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(succeeded.get(), rejected.get());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static io.restassured.response.ValidatableResponse createDomain(String body) {
        return given().contentType(ContentType.JSON).body(body)
            .when().post("/domainnames").then().statusCode(201);
    }

    private static void deleteDomain(String domain) {
        given().when().delete("/domainnames/" + domain).then().statusCode(202);
    }
}
