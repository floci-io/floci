package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotIntegrationTest {

    @Test
    @Order(1)
    void unsupportedEndpointTypeReturnsAwsError() {
        given()
            .queryParam("endpointType", "unsupported")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(2)
    void defaultDescribeEndpointReturnsBaseUrlAuthority() {
        given()
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("localhost:4566"));
    }

    @Test
    @Order(3)
    void describeEndpointAcceptsAwsDefaultEndpointType() {
        given()
            .queryParam("endpointType", "iot:Data-ATS")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo("localhost:4566"));
    }

    @Test
    @Order(4)
    void describeMissingThingReturnsAwsError() {
        given()
        .when()
            .get("/things/missing-thing")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(5)
    void createThingReturnsAwsShape() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "attributePayload": {
                    "attributes": {
                      "env": "test"
                    }
                  }
                }
                """)
        .when()
            .post("/things/phase-two-thing")
        .then()
            .statusCode(200)
            .body("thingName", equalTo("phase-two-thing"))
            .body("thingArn", containsString(":iot:us-east-1:000000000000:thing/phase-two-thing"))
            .body("thingId", notNullValue())
            .body("version", equalTo(1))
            .body("attributes.env", equalTo("test"));
    }

    @Test
    @Order(6)
    void identicalCreateThingIsIdempotent() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "attributePayload": {
                    "attributes": {
                      "env": "test"
                    }
                  }
                }
                """)
        .when()
            .post("/things/phase-two-thing")
        .then()
            .statusCode(200)
            .body("thingName", equalTo("phase-two-thing"))
            .body("attributes.env", equalTo("test"));
    }

    @Test
    @Order(7)
    void duplicateCreateThingReturnsConflict() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/phase-two-thing")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(8)
    void describeThingReturnsStoredThing() {
        given()
        .when()
            .get("/things/phase-two-thing")
        .then()
            .statusCode(200)
            .body("thingName", equalTo("phase-two-thing"))
            .body("attributes.env", equalTo("test"))
            .body("creationDate", notNullValue())
            .body("lastModifiedDate", notNullValue());
    }

    @Test
    @Order(9)
    void listThingsIncludesCreatedThing() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/phase-two-other")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/things")
        .then()
            .statusCode(200)
            .body("things.thingName", hasItem("phase-two-thing"))
            .body("things.thingName", hasItem("phase-two-other"));

        String nextToken = given()
            .queryParam("maxResults", 1)
        .when()
            .get("/things")
        .then()
            .statusCode(200)
            .body("things.size()", equalTo(1))
            .body("nextToken", notNullValue())
            .extract()
            .path("nextToken");

        given()
            .queryParam("maxResults", 1)
            .queryParam("nextToken", nextToken)
        .when()
            .get("/things")
        .then()
            .statusCode(200)
            .body("things.size()", equalTo(1));
    }

    @Test
    @Order(10)
    void updateThingChangesAttributes() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "attributePayload": {
                    "attributes": {
                      "env": "updated",
                      "owner": "iot"
                    }
                  }
                }
                """)
        .when()
            .patch("/things/phase-two-thing")
        .then()
            .statusCode(200)
            .body("version", equalTo(2));

        given()
            .contentType("application/json")
            .body("""
                {
                  "expectedVersion": 2,
                  "attributePayload": {
                    "attributes": {
                      "env": "versioned",
                      "owner": "iot"
                    }
                  }
                }
                """)
        .when()
            .patch("/things/phase-two-thing")
        .then()
            .statusCode(200)
            .body("version", equalTo(3));

        given()
            .contentType("application/json")
            .body("""
                {
                  "expectedVersion": 2,
                  "attributePayload": {
                    "attributes": {
                      "env": "stale"
                    }
                  }
                }
                """)
        .when()
            .patch("/things/phase-two-thing")
        .then()
            .statusCode(409)
            .body("__type", equalTo("VersionConflictException"));

        given()
        .when()
            .get("/things/phase-two-thing")
        .then()
            .statusCode(200)
            .body("attributes.env", equalTo("versioned"))
            .body("attributes.owner", equalTo("iot"));
    }

    @Test
    @Order(11)
    void deleteThingRemovesThing() {
        given()
        .when()
            .delete("/things/phase-two-thing")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/things/phase-two-thing")
        .then()
            .statusCode(404);

        given()
        .when()
            .get("/things")
        .then()
            .statusCode(200)
            .body("things.thingName", not(hasItem("phase-two-thing")))
            .body("things.thingName", hasItem("phase-two-other"));
    }

    @Test
    @Order(12)
    void listTagsForUntaggedThingReturnsEmptyList() {
        String thingArn = createThingAndReturnArn("phase-three-untagged");

        given()
            .queryParam("resourceArn", thingArn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.size()", equalTo(0));
    }

    @Test
    @Order(13)
    void tagResourceAddsThingTags() {
        String thingArn = createThingAndReturnArn("phase-three-tagged");

        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "%s",
                  "tags": [
                    {"Key": "env", "Value": "test"},
                    {"Key": "owner", "Value": "iot"}
                  ]
                }
                """.formatted(thingArn))
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", thingArn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("env"))
            .body("tags.Value", hasItem("test"))
            .body("tags.Key", hasItem("owner"))
            .body("tags.Value", hasItem("iot"));
    }

    @Test
    @Order(14)
    void untagResourceRemovesSelectedThingTags() {
        String thingArn = createThingAndReturnArn("phase-three-untag");

        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "%s",
                  "tags": [
                    {"Key": "env", "Value": "test"},
                    {"Key": "owner", "Value": "iot"}
                  ]
                }
                """.formatted(thingArn))
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "%s",
                  "tagKeys": ["env"]
                }
                """.formatted(thingArn))
        .when()
            .post("/untag")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", thingArn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", not(hasItem("env")))
            .body("tags.Key", hasItem("owner"));
    }

    @Test
    @Order(15)
    void taggingMissingThingReturnsAwsError() {
        String missingThingArn = "arn:aws:iot:us-east-1:000000000000:thing/phase-three-missing";

        given()
            .queryParam("resourceArn", missingThingArn)
        .when()
            .get("/tags")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(16)
    void certificatesPoliciesAndAttachmentsRoundTrip() {
        String certificateArn = given()
            .contentType("application/json")
            .queryParam("setAsActive", true)
            .body("{}")
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .body("certificateId", matchesPattern("[0-9a-f]{64}"))
            .body("certificateArn", containsString(":iot:us-east-1:000000000000:cert/"))
            .body("certificatePem", containsString("BEGIN CERTIFICATE"))
            .body("keyPair.PublicKey", containsString("BEGIN PUBLIC KEY"))
            .body("keyPair.PrivateKey", containsString("BEGIN RSA PRIVATE KEY"))
            .extract()
            .path("certificateArn");
        String certificateId = certificateArn.substring(certificateArn.lastIndexOf('/') + 1);

        given()
        .when()
            .get("/certificates/" + certificateId)
        .then()
            .statusCode(200)
            .body("certificateDescription.status", equalTo("ACTIVE"))
            .body("certificateDescription.certificateArn", equalTo(certificateArn));

        given()
        .when()
            .get("/certificates")
        .then()
            .statusCode(200)
            .body("certificates.certificateArn", hasItem(certificateArn));

        given()
            .contentType("application/json")
            .body("{\"newStatus\":\"INACTIVE\"}")
        .when()
            .put("/certificates/" + certificateId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/certificates/" + certificateId)
        .then()
            .statusCode(200)
            .body("certificateDescription.status", equalTo("INACTIVE"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "policyDocument": "{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}"
                }
                """)
        .when()
            .post("/policies/phase-four-policy")
        .then()
            .statusCode(200)
            .body("policyName", equalTo("phase-four-policy"));

        given()
        .when()
            .get("/policies/phase-four-policy")
        .then()
            .statusCode(200)
            .body("policyName", equalTo("phase-four-policy"))
            .body("policyDocument", containsString("2012-10-17"));

        given()
        .when()
            .get("/policies")
        .then()
            .statusCode(200)
            .body("policies.policyName", hasItem("phase-four-policy"));

        given()
            .queryParam("target", certificateArn)
        .when()
            .put("/target-policies/phase-four-policy")
        .then()
            .statusCode(200);

        String thingArn = createThingAndReturnArn("phase-four-principal-thing");
        given()
            .queryParam("principal", certificateArn)
        .when()
            .put("/things/phase-four-principal-thing/principals")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/things/phase-four-principal-thing/principals")
        .then()
            .statusCode(200)
            .body("principals", hasItem(certificateArn));

        given()
            .queryParam("principal", certificateArn)
        .when()
            .delete("/things/phase-four-principal-thing/principals")
        .then()
            .statusCode(200);

        given()
            .queryParam("target", certificateArn)
        .when()
            .post("/target-policies/phase-four-policy")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(17)
    void certificateDeleteCsrStatusAndTagsMatchMvpLifecycle() throws Exception {
        String activeArn = given()
            .queryParam("setAsActive", true)
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract()
            .path("certificateArn");
        String activeId = activeArn.substring(activeArn.lastIndexOf('/') + 1);

        given()
        .when()
            .delete("/certificates/" + activeId)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .contentType("application/json")
            .body("{\"newStatus\":\"PENDING_TRANSFER\"}")
        .when()
            .put("/certificates/" + activeId)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .contentType("application/json")
            .body("{\"newStatus\":\"INACTIVE\"}")
        .when()
            .put("/certificates/" + activeId)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "%s",
                  "tags": [{"Key": "env", "Value": "cert"}]
                }
                """.formatted(activeArn))
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", activeArn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("env"))
            .body("tags.Value", hasItem("cert"));

        given()
        .when()
            .delete("/certificates/" + activeId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/certificates/" + activeId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        String csrArn = given()
            .contentType("application/json")
            .queryParam("setAsActive", false)
            .body(Map.of("certificateSigningRequest", csrPem()))
        .when()
            .post("/certificates")
        .then()
            .statusCode(200)
            .body("certificatePem", containsString("BEGIN CERTIFICATE"))
            .extract()
            .path("certificateArn");
        String csrId = csrArn.substring(csrArn.lastIndexOf('/') + 1);

        given()
        .when()
            .delete("/certificates/" + csrId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(18)
    void certificateDeleteRejectsThingPrincipalAttachment() {
        String certificateArn = given()
            .queryParam("setAsActive", false)
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract()
            .path("certificateArn");
        String certificateId = certificateArn.substring(certificateArn.lastIndexOf('/') + 1);

        createThingAndReturnArn("mvp1-attached-cert-thing");
        given()
            .queryParam("principal", certificateArn)
        .when()
            .put("/things/mvp1-attached-cert-thing/principals")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/certificates/" + certificateId)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .queryParam("principal", certificateArn)
        .when()
            .get("/principals/things")
        .then()
            .statusCode(200)
            .body("things", hasItem("mvp1-attached-cert-thing"));
    }

    @Test
    @Order(19)
    void policyCrudVersionsAttachmentReadsAndTagsMatchMvpLifecycle() {
        given()
            .contentType("application/json")
            .body("{\"policyDocument\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}\"}")
        .when()
            .post("/policies/phase-four-policy")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"));

        String policyArn = given()
            .contentType("application/json")
            .body("{\"policyDocument\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}\"}")
        .when()
            .post("/policies/mvp1-policy")
        .then()
            .statusCode(200)
            .extract()
            .path("policyArn");

        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "%s",
                  "tags": [{"Key": "env", "Value": "policy"}]
                }
                """.formatted(policyArn))
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        String versionId = given()
            .contentType("application/json")
            .queryParam("setAsDefault", true)
            .body("{\"policyDocument\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\"}]}\"}")
        .when()
            .post("/policies/mvp1-policy/version")
        .then()
            .statusCode(200)
            .body("isDefaultVersion", equalTo(true))
            .extract()
            .path("policyVersionId");

        given()
        .when()
            .get("/policies/mvp1-policy/version")
        .then()
            .statusCode(200)
            .body("policyVersions.versionId", hasItem(versionId));

        given()
        .when()
            .get("/policies/mvp1-policy/version/" + versionId)
        .then()
            .statusCode(200)
            .body("policyDocument", containsString("Allow"));

        given()
        .when()
            .patch("/policies/mvp1-policy/version/1")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/policies/mvp1-policy/version/" + versionId)
        .then()
            .statusCode(200);

        String certificateArn = given()
            .queryParam("setAsActive", false)
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract()
            .path("certificateArn");

        given()
            .queryParam("target", certificateArn)
        .when()
            .put("/target-policies/mvp1-policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/attached-policies/" + certificateArn)
        .then()
            .statusCode(200)
            .body("policies.policyName", hasItem("mvp1-policy"));

        given()
        .when()
            .get("/policy-targets/mvp1-policy")
        .then()
            .statusCode(200)
            .body("targets", hasItem(certificateArn));

        given()
        .when()
            .delete("/policies/mvp1-policy")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .queryParam("target", certificateArn)
        .when()
            .post("/target-policies/mvp1-policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/policies/mvp1-policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/policies/mvp1-policy")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(20)
    void aSixthPolicyVersionIsRefusedUntilOneIsDeleted() {
        String document = "{\"policyDocument\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}\"}";
        given()
            .contentType("application/json")
            .body(document)
        .when()
            .post("/policies/five-versions")
        .then()
            .statusCode(200);
        for (int version = 2; version <= 5; version++) {
            given()
                .contentType("application/json")
                .body(document)
            .when()
                .post("/policies/five-versions/version")
            .then()
                .statusCode(200)
                .body("policyVersionId", equalTo(Integer.toString(version)));
        }

        given()
            .contentType("application/json")
            .body(document)
        .when()
            .post("/policies/five-versions/version")
        .then()
            .statusCode(409)
            .body("__type", equalTo("VersionsLimitExceededException"));

        given()
        .when()
            .delete("/policies/five-versions/version/3")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .body(document)
        .when()
            .post("/policies/five-versions/version")
        .then()
            .statusCode(200)
            .body("policyVersionId", equalTo("6"));
    }

    @Test
    @Order(21)
    void registerCertificateWithoutCaTrustsAnyIssuer() {
        String certificatePem = new CertificateGenerator()
                .generateSelfSignedCertificate("no-ca-device", List.of(), KeyAlgorithm.RSA_2048)
                .certificatePem();

        Map<String, String> registered = given()
            .contentType("application/json")
            .body(Map.of("certificatePem", certificatePem, "status", "ACTIVE"))
        .when()
            .post("/certificate/register-no-ca")
        .then()
            .statusCode(200)
            .body("certificateId", matchesPattern("[0-9a-f]{64}"))
            .body("certificateArn", containsString(":iot:us-east-1:000000000000:cert/"))
            .extract()
            .path("");
        String certificateId = registered.get("certificateId");

        given()
        .when()
            .get("/certificates/" + certificateId)
        .then()
            .statusCode(200)
            .body("certificateDescription.status", equalTo("ACTIVE"))
            .body("certificateDescription.certificateMode", equalTo("SNI_ONLY"))
            .body("certificateDescription.certificatePem", equalTo(certificatePem));

        given()
        .when()
            .get("/certificates")
        .then()
            .statusCode(200)
            .body("certificates.find { it.certificateId == '" + certificateId + "' }.certificateMode", equalTo("SNI_ONLY"));

        given()
            .contentType("application/json")
            .body(Map.of("certificatePem", certificatePem))
        .when()
            .post("/certificate/register-no-ca")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"))
            .body("resourceId", equalTo(certificateId))
            .body("resourceArn", equalTo(registered.get("certificateArn")));

        given()
            .contentType("application/json")
            .body(Map.of("certificatePem", "not a certificate"))
        .when()
            .post("/certificate/register-no-ca")
        .then()
            .statusCode(400)
            .body("__type", equalTo("CertificateValidationException"));

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/certificate/register-no-ca")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .contentType("application/json")
            .body(Map.of("certificatePem", certificatePem, "status", "UNKNOWN"))
        .when()
            .post("/certificate/register-no-ca")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(22)
    void principalListsV2ReportTheAttachmentType() {
        String certificateArn = given()
            .queryParam("setAsActive", true)
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .extract()
            .path("certificateArn");
        createThingAndReturnArn("v2-principal-thing-a");
        createThingAndReturnArn("v2-principal-thing-b");
        for (String thingName : List.of("v2-principal-thing-a", "v2-principal-thing-b")) {
            given()
                .queryParam("principal", certificateArn)
            .when()
                .put("/things/" + thingName + "/principals")
            .then()
                .statusCode(200);
        }

        given()
        .when()
            .get("/things/v2-principal-thing-a/principals-v2")
        .then()
            .statusCode(200)
            .body("thingPrincipalObjects.principal", equalTo(List.of(certificateArn)))
            .body("thingPrincipalObjects.thingPrincipalType", equalTo(List.of("NON_EXCLUSIVE_THING")));

        given()
            .header("x-amzn-principal", certificateArn)
            .queryParam("maxResults", 1)
        .when()
            .get("/principals/things-v2")
        .then()
            .statusCode(200)
            .body("principalThingObjects.thingName", equalTo(List.of("v2-principal-thing-a")))
            .body("principalThingObjects.thingPrincipalType", equalTo(List.of("NON_EXCLUSIVE_THING")))
            .body("nextToken", equalTo("1"));

        given()
            .header("x-amzn-principal", certificateArn)
            .queryParam("nextToken", "1")
        .when()
            .get("/principals/things-v2")
        .then()
            .statusCode(200)
            .body("principalThingObjects.thingName", equalTo(List.of("v2-principal-thing-b")))
            .body("nextToken", nullValue());

        given()
            .header("x-amzn-principal", certificateArn)
            .queryParam("thingPrincipalType", "EXCLUSIVE_THING")
        .when()
            .get("/principals/things-v2")
        .then()
            .statusCode(200)
            .body("principalThingObjects", empty());

        given()
        .when()
            .get("/principals/things-v2")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .header("x-amzn-principal", certificateArn)
            .queryParam("maxResults", 0)
        .when()
            .get("/principals/things-v2")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));

        given()
            .queryParam("maxResults", 251)
        .when()
            .get("/things/v2-principal-thing-a/principals-v2")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(23)
    void updateThingMergesAttributesAndSetsAndRemovesTheThingType() {
        given()
            .contentType("application/json")
            .body("{\"thingTypeProperties\":{\"thingTypeDescription\":\"update semantics\"}}")
        .when()
            .post("/thing-types/update-semantics-type")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .body("{\"attributePayload\":{\"attributes\":{\"a\":\"1\",\"b\":\"2\"}}}")
        .when()
            .post("/things/update-semantics-thing")
        .then()
            .statusCode(200);

        patchThing("update-semantics-thing", "{\"attributePayload\":{\"attributes\":{\"b\":\"\",\"c\":\"3\"},\"merge\":true}}", 2);
        patchThing("update-semantics-thing", "{\"attributePayload\":{\"merge\":true}}", 3);

        given()
        .when()
            .get("/things/update-semantics-thing")
        .then()
            .statusCode(200)
            .body("attributes.size()", equalTo(2))
            .body("attributes.a", equalTo("1"))
            .body("attributes.c", equalTo("3"))
            .body("attributes.b", nullValue())
            .body("attributes.merge", nullValue());

        patchThing("update-semantics-thing", "{\"thingTypeName\":\"update-semantics-type\"}", 4);

        given()
        .when()
            .get("/things/update-semantics-thing")
        .then()
            .statusCode(200)
            .body("thingTypeName", equalTo("update-semantics-type"))
            .body("attributes.size()", equalTo(2));

        patchThing("update-semantics-thing", "{\"removeThingType\":true}", 5);

        given()
        .when()
            .get("/things/update-semantics-thing")
        .then()
            .statusCode(200)
            .body("thingTypeName", nullValue())
            .body("attributes.a", equalTo("1"))
            .body("version", equalTo(5));

        given().when().delete("/things/update-semantics-thing").then().statusCode(200);
        given().when().post("/thing-types/update-semantics-type/deprecate").then().statusCode(200);
        given().when().delete("/thing-types/update-semantics-type").then().statusCode(200);
    }

    @Test
    @Order(24)
    void deleteThingWithAStaleExpectedVersionIsAVersionConflict() {
        createThingAndReturnArn("stale-delete-thing");

        given()
            .queryParam("expectedVersion", 7)
        .when()
            .delete("/things/stale-delete-thing")
        .then()
            .statusCode(409)
            .body("__type", equalTo("VersionConflictException"))
            .body("message", equalTo("The version for thing stale-delete-thing does not match the expected version."));

        given()
        .when()
            .get("/things/stale-delete-thing")
        .then()
            .statusCode(200);

        given()
            .queryParam("expectedVersion", 1)
        .when()
            .delete("/things/stale-delete-thing")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/things/stale-delete-thing")
        .then()
            .statusCode(404);
    }

    private static void patchThing(String thingName, String body, int expectedVersionAfter) {
        given()
            .contentType("application/json")
            .body(body)
        .when()
            .patch("/things/" + thingName)
        .then()
            .statusCode(200)
            .body("version", equalTo(expectedVersionAfter));
    }

    private String createThingAndReturnArn(String thingName) {
        return given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/" + thingName)
        .then()
            .statusCode(200)
            .extract()
            .path("thingArn");
    }


    private static String csrPem() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=round-trip"), kp.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate()));
        StringWriter out = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(out)) {
            w.writeObject(csr);
        }
        return out.toString();
    }
}
