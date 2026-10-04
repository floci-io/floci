package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Custom domain names and API mappings over the HTTP API (v2) endpoints.
 *
 * <p>Before these routes existed, {@code POST /v2/domainnames} matched the S3 controller's
 * catch-all instead and came back as an S3 XML error about multipart uploads, which an SDK
 * expecting JSON cannot even deserialize.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2DomainNameIntegrationTest {

    private static final String DOMAIN = "v2-domain-test.example.com";
    private static String apiId;
    private static String apiMappingId;

    @Test
    @Order(1)
    void createDomainNameIsRoutedToApiGateway() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"%s","domainNameConfigurations":[
                        {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc",
                         "endpointType":"REGIONAL","securityPolicy":"TLS_1_2"}]}
                    """.formatted(DOMAIN))
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(201)
            .contentType(ContentType.JSON)
            .body("domainName", is(DOMAIN))
            .body("domainNameArn", containsString("/domainnames/" + DOMAIN))
            .body("apiMappingSelectionExpression", is("$request.basepath"))
            .body("domainNameConfigurations[0].endpointType", is("REGIONAL"))
            .body("domainNameConfigurations[0].securityPolicy", is("TLS_1_2"))
            .body("domainNameConfigurations[0].domainNameStatus", is("AVAILABLE"))
            .body("domainNameConfigurations[0].apiGatewayDomainName", notNullValue())
            .body("domainNameConfigurations[0].hostedZoneId", notNullValue())
            .body("domainNameConfigurations[0].certificateArn", containsString("certificate/abc"))
            .body("domainNameConfigurations[0].ipAddressType", is("ipv4"))
            .body("routingMode", is("API_MAPPING_ONLY"))
            // The symptom this replaces: an S3 error for a call S3 was never asked to serve.
            .body(not(containsString("uploads")));
    }

    @Test
    @Order(2)
    void duplicateDomainNameIsABadRequest() {
        // Verified against AWS, which answers BadRequestException here rather than a conflict.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"%s","domainNameConfigurations":[{"endpointType":"REGIONAL"}]}
                    """.formatted(DOMAIN))
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(400)
            .body(containsString("already exists"));
    }

    @Test
    @Order(3)
    void aDomainNameConfigurationIsRequired() {
        // AWS: "Invalid input. Expected one domain name configuration" — the REST API takes none,
        // so the two endpoints do not share this rule.
        given()
            .contentType(ContentType.JSON)
            .body("{\"domainName\":\"no-configuration.example.com\"}")
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(400)
            .body(containsString("Expected one domain name configuration"));
    }

    @Test
    @Order(3)
    void tagsAreKeptRatherThanDropped() {
        // Dropping them would leave terraform with a diff it can never settle.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"tagged.example.com",
                     "domainNameConfigurations":[{"endpointType":"REGIONAL"}],
                     "tags":{"env":"prod"}}
                    """)
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(201)
            .body("tags.env", is("prod"));

        given()
        .when()
            .get("/v2/domainnames/tagged.example.com")
        .then()
            .statusCode(200)
            .body("tags.env", is("prod"));
    }

    @Test
    @Order(3)
    void anUnsupportedRoutingModeIsRefused() {
        // floci does not emulate routing rules, so it says so rather than quietly creating a
        // domain that routes by API mapping regardless.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"routing.example.com",
                     "domainNameConfigurations":[{"endpointType":"REGIONAL"}],
                     "routingMode":"ROUTING_RULE_ONLY"}
                    """)
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(400)
            .body(containsString("API_MAPPING_ONLY"));
    }

    @Test
    @Order(4)
    void getAndListDomainNames() {
        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN)
        .then()
            .statusCode(200)
            .body("domainName", is(DOMAIN));

        given()
        .when()
            .get("/v2/domainnames")
        .then()
            .statusCode(200)
            .body("items.domainName", hasItemEqualTo(DOMAIN));
    }

    @Test
    @Order(5)
    void domainIsTheSameResourceThroughBothApis() {
        // AWS exposes one custom domain through both APIs, so what v2 created v1 must also see.
        given()
        .when()
            .get("/domainnames/" + DOMAIN)
        .then()
            .statusCode(200)
            .body("domainName", is(DOMAIN));
    }

    @Test
    @Order(6)
    void createApiMapping() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"v2-domain-api\",\"protocolType\":\"HTTP\"}")
            .when()
                .post("/v2/apis")
            .then()
                .statusCode(201)
                .extract().path("apiId");

        // A mapping needs a stage that exists, so the API gets one before it is mapped.
        given()
            .contentType(ContentType.JSON)
            .body("{\"stageName\":\"$default\"}")
        .when()
            .post("/v2/apis/" + apiId + "/stages")
        .then()
            .statusCode(201);

        apiMappingId = given()
                .contentType(ContentType.JSON)
                .body("{\"apiId\":\"%s\",\"stage\":\"$default\",\"apiMappingKey\":\"orders\"}".formatted(apiId))
            .when()
                .post("/v2/domainnames/" + DOMAIN + "/apimappings")
            .then()
                .statusCode(201)
                .body("apiId", is(apiId))
                .body("stage", is("$default"))
                .body("apiMappingKey", is("orders"))
                .body("apiMappingId", notNullValue())
                .extract().path("apiMappingId");
    }

    @Test
    @Order(7)
    void aMappingNeedsAnApiAndStageThatExist() {
        // Verified against AWS: both are BadRequestException rather than a 201 for a mapping that
        // routes nowhere.
        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"zzzzzzzzzz\",\"stage\":\"$default\"}")
        .when()
            .post("/v2/domainnames/" + DOMAIN + "/apimappings")
        .then()
            .statusCode(400)
            .body(containsString("Invalid API identifier specified"));

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\",\"stage\":\"nosuchstage\"}".formatted(apiId))
        .when()
            .post("/v2/domainnames/" + DOMAIN + "/apimappings")
        .then()
            .statusCode(400)
            .body(containsString("Invalid stage identifier specified"));
    }

    @Test
    @Order(7)
    void anOmittedAndAnEmptyMappingKeyAreTheSameMapping() {
        // AWS answers the second with ConflictException, so the two spellings must land on one
        // record rather than creating two apparent root mappings.
        String rootMapping = "{\"apiId\":\"%s\",\"stage\":\"$default\"}".formatted(apiId);
        given()
            .contentType(ContentType.JSON)
            .body(rootMapping)
        .when()
            .post("/v2/domainnames/" + DOMAIN + "/apimappings")
        .then()
            .statusCode(201)
            .body("apiMappingKey", is(""));

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\",\"stage\":\"$default\",\"apiMappingKey\":\"\"}".formatted(apiId))
        .when()
            .post("/v2/domainnames/" + DOMAIN + "/apimappings")
        .then()
            .statusCode(409)
            .body(containsString("already exists"));

        // And the root mapping is reachable through the v1 endpoint, which spells it "(none)".
        given()
        .when()
            .get("/domainnames/" + DOMAIN + "/basepathmappings/(none)")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void rootSpellingsCollapseToOneRecordOnWrite() {
        // The read path has always normalised the root to "(none)"; the write path now does too, so
        // "/" cannot end up stored beside it as a second record that means the same thing — and a
        // mapping created as "" can be read back as "", which it could not before.
        String otherDomain = "root-spelling.example.com";
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"%s","domainNameConfigurations":[{"endpointType":"REGIONAL"}]}
                    """.formatted(otherDomain))
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"restApiId\":\"%s\",\"stage\":\"$default\",\"basePath\":\"\"}".formatted(apiId))
        .when()
            .post("/domainnames/" + otherDomain + "/basepathmappings")
        .then()
            .statusCode(201);

        given()
        .when()
            .get("/domainnames/" + otherDomain + "/basepathmappings/(none)")
        .then()
            .statusCode(200);

        // One record, whichever spelling created it.
        given()
        .when()
            .get("/v2/domainnames/" + otherDomain + "/apimappings")
        .then()
            .statusCode(200)
            .body("items.size()", is(1))
            .body("items[0].apiMappingKey", is(""));
    }

    @Test
    @Order(7)
    void mappingIdsDoNotCollide() {
        // Ids derived by hashing would collide: "Aa" and "BB" share a Java String hashCode, so a
        // read or delete by that id would pick between the two mappings arbitrarily.
        String first = createMapping("Aa");
        String second = createMapping("BB");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + first)
        .then()
            .statusCode(200)
            .body("apiMappingKey", is("Aa"));

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + second)
        .then()
            .statusCode(200)
            .body("apiMappingKey", is("BB"));
    }

    private String createMapping(String key) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"apiId\":\"%s\",\"stage\":\"$default\",\"apiMappingKey\":\"%s\"}".formatted(apiId, key))
            .when()
                .post("/v2/domainnames/" + DOMAIN + "/apimappings")
            .then()
                .statusCode(201)
                .extract().path("apiMappingId");
    }

    @Test
    @Order(8)
    void anApiStillMappedToADomainCannotBeDeleted() {
        // Verified against AWS, which refuses rather than leaving the mapping pointing at an API
        // that no longer exists.
        given()
        .when()
            .delete("/v2/apis/" + apiId)
        .then()
            .statusCode(400)
            .body(containsString("remove all API mappings"));

        given()
        .when()
            .get("/v2/apis/" + apiId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void inputsFlociCannotHonourAreRefused() {
        // Accepting these would answer with a domain that behaves differently from the request.
        String[] bodies = {
            """
            {"domainName":"mtls.example.com","domainNameConfigurations":[{"endpointType":"REGIONAL"}],
             "mutualTlsAuthentication":{"truststoreUri":"s3://bucket/truststore.pem"}}""",
            """
            {"domainName":"dualstack.example.com",
             "domainNameConfigurations":[{"endpointType":"REGIONAL","ipAddressType":"dualstack"}]}""",
            """
            {"domainName":"ownership.example.com","domainNameConfigurations":[
                {"endpointType":"REGIONAL","ownershipVerificationCertificateArn":"arn:aws:acm:us-east-1:0:certificate/o"}]}"""
        };
        for (String body : bodies) {
            given()
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/v2/domainnames")
            .then()
                .statusCode(400);
        }
    }

    @Test
    @Order(8)
    void getApiMappingByItsId() {
        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + apiMappingId)
        .then()
            .statusCode(200)
            .body("apiMappingId", is(apiMappingId))
            .body("apiId", is(apiId));

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings")
        .then()
            .statusCode(200)
            .body("items.apiMappingId", hasItemEqualTo(apiMappingId));
    }

    @Test
    @Order(9)
    void unknownApiMappingIsNotFound() {
        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/does-not-exist")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(10)
    void deleteApiMappingThenDomainName() {
        given()
        .when()
            .delete("/v2/domainnames/" + DOMAIN + "/apimappings/" + apiMappingId)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + apiMappingId)
        .then()
            .statusCode(404);

        given()
        .when()
            .delete("/v2/domainnames/" + DOMAIN)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(11)
    void aRestApiCanBeMappedUnderAKeyWithSeveralLevels() {
        // AWS takes a REST API here too: the v2 API is how a REST API gets a multi-level mapping.
        String restApiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"v2-mapped-rest-api\"}")
            .when()
                .post("/restapis")
            .then()
                .statusCode(201)
                .extract().path("id");
        given()
            .contentType(ContentType.JSON)
            .body("{\"stageName\":\"prod\"}")
        .when()
            .post("/restapis/" + restApiId + "/deployments")
        .then()
            .statusCode(201);
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"rest-multi-level.example.com","domainNameConfigurations":[
                        {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc","endpointType":"REGIONAL"}]}
                    """)
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\",\"stage\":\"prod\",\"apiMappingKey\":\"orders/v1/items\"}".formatted(restApiId))
        .when()
            .post("/v2/domainnames/rest-multi-level.example.com/apimappings")
        .then()
            .statusCode(201)
            .body("apiId", is(restApiId))
            .body("apiMappingKey", is("orders/v1/items"));
    }

    @Test
    @Order(12)
    void anEdgeDomainCannotBeManagedThroughTheV2Api() {
        String message = "Only REGIONAL domain names can be managed through the API Gateway V2 API. For EDGE "
                + "domain names, please use the API Gateway V1 API. Also note that only REST APIs can be attached "
                + "to EDGE domain names.";
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"edge-only.example.com",
                     "certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc",
                     "endpointConfiguration":{"types":["EDGE"]}}
                    """)
        .when()
            .post("/domainnames")
        .then()
            .statusCode(201);

        given()
        .when()
            .get("/v2/domainnames/edge-only.example.com/apimappings")
        .then()
            .statusCode(400)
            .body("message", is(message));
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"edge-v2.example.com","domainNameConfigurations":[{"endpointType":"EDGE"}]}
                    """)
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(400)
            // Measured against API Gateway (eu-west-1, 2026-10-04): a v2 create names the endpoint type.
            .body("message", is("EDGE endpoint type is not supported for APIGatewayV2 domainName"));
    }

    @Test
    @Order(13)
    void anHttpApiIsMappedOnlyOntoADomainOnTls12() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"tls10.example.com",
                     "regionalCertificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc",
                     "endpointConfiguration":{"types":["REGIONAL"]},"securityPolicy":"TLS_1_0"}
                    """)
        .when()
            .post("/domainnames")
        .then()
            .statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\",\"stage\":\"$default\"}".formatted(apiId))
        .when()
            .post("/v2/domainnames/tls10.example.com/apimappings")
        .then()
            .statusCode(400)
            .body(containsString("TLS 1.2"));
    }

    /** Measured against API Gateway (eu-west-1, 2026-10-04): what an update leaves out keeps its value. */
    @Test
    @Order(14)
    void updateApiMappingChangesOnlyWhatItNames() {
        String domain = "patch-mapping.example.com";
        createRegionalDomain(domain);
        given()
            .contentType(ContentType.JSON)
            .body("{\"stageName\":\"s2\"}")
        .when()
            .post("/v2/apis/" + apiId + "/stages")
        .then()
            .statusCode(201);
        String id = given()
                .contentType(ContentType.JSON)
                .body("{\"apiId\":\"%s\",\"stage\":\"$default\",\"apiMappingKey\":\"patched\"}".formatted(apiId))
            .when()
                .post("/v2/domainnames/" + domain + "/apimappings")
            .then()
                .statusCode(201)
                .extract().path("apiMappingId");

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\",\"stage\":\"s2\"}".formatted(apiId))
        .when()
            .patch("/v2/domainnames/" + domain + "/apimappings/" + id)
        .then()
            .statusCode(200)
            .body("apiMappingId", is(id))
            .body("apiMappingKey", is("patched"))
            .body("stage", is("s2"));

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\",\"apiMappingKey\":\"patched-again\"}".formatted(apiId))
        .when()
            .patch("/v2/domainnames/" + domain + "/apimappings/" + id)
        .then()
            .statusCode(200)
            .body("apiMappingId", is(id))
            .body("apiMappingKey", is("patched-again"))
            .body("stage", is("s2"));

        given()
            .contentType(ContentType.JSON)
            .body("{\"apiId\":\"%s\"}".formatted(apiId))
        .when()
            .patch("/v2/domainnames/" + domain + "/apimappings/zzzzzz")
        .then()
            .statusCode(404);
    }

    /** Measured against API Gateway (eu-west-1, 2026-10-04): an HTTP API domain stays regional. */
    @Test
    @Order(14)
    void updateDomainNameKeepsTheDomainRegional() {
        String domain = "patch-domain.example.com";
        createRegionalDomain(domain);
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainNameConfigurations":[
                        {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/def","endpointType":"PRIVATE"}]}
                    """)
        .when()
            .patch("/v2/domainnames/" + domain)
        .then()
            .statusCode(200)
            .body("domainNameConfigurations[0].endpointType", is("REGIONAL"))
            .body("domainNameConfigurations[0].certificateArn", containsString("certificate/def"));

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainNameConfigurations":[
                        {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/def","endpointType":"EDGE"}]}
                    """)
        .when()
            .patch("/v2/domainnames/" + domain)
        .then()
            .statusCode(400)
            .body("message", is("EDGE endpoint type is not supported for APIGatewayV2 domainName"));
    }

    private static void createRegionalDomain(String domain) {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"domainName":"%s","domainNameConfigurations":[
                        {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc","endpointType":"REGIONAL"}]}
                    """.formatted(domain))
        .when()
            .post("/v2/domainnames")
        .then()
            .statusCode(201);
    }

    private static org.hamcrest.Matcher<Iterable<? super String>> hasItemEqualTo(String value) {
        return org.hamcrest.Matchers.hasItem(equalTo(value));
    }
}
