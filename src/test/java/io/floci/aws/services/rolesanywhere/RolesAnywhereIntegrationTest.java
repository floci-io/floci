package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rolesanywhere.RolesAnywhereClient;
import software.amazon.awssdk.services.rolesanywhere.model.AttributeMapping;
import software.amazon.awssdk.services.rolesanywhere.model.CertificateField;
import software.amazon.awssdk.services.rolesanywhere.model.CrlDetail;
import software.amazon.awssdk.services.rolesanywhere.model.NotificationEvent;
import software.amazon.awssdk.services.rolesanywhere.model.NotificationSettingDetail;
import software.amazon.awssdk.services.rolesanywhere.model.ProfileDetail;
import software.amazon.awssdk.services.rolesanywhere.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rolesanywhere.model.Tag;
import software.amazon.awssdk.services.rolesanywhere.model.TrustAnchorDetail;
import software.amazon.awssdk.services.rolesanywhere.model.TrustAnchorType;
import software.amazon.awssdk.services.rolesanywhere.model.ValidationException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class RolesAnywhereIntegrationTest {

    private static final String ACCOUNT_ID = "724681000041";
    private static final String REGION = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/workload";
    private static final String TIMESTAMP = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";
    private static final String ARN_PREFIX = "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT_ID + ":";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestHTTPResource
    URI endpoint;

    @Test
    void trustAnchorRoundTripsThroughRestJsonIncludingAnArnInThePath() {
        ObjectNode create = anchorBody("it-anchor");
        create.put("aliasName", "it/anchor");
        Response created = rolesAnywhere()
                .body(create.toString())
        .when()
                .post("/trustanchors")
        .then()
                .statusCode(201)
                .body("trustAnchor.trustAnchorId", matchesPattern("[a-f0-9-]{36}"))
                .body("trustAnchor.trustAnchorArn", startsWith(ARN_PREFIX + "trust-anchor/"))
                .body("trustAnchor.name", equalTo("it-anchor"))
                .body("trustAnchor.source.sourceType", equalTo("CERTIFICATE_BUNDLE"))
                .body("trustAnchor.source.sourceData.x509CertificateData",
                        equalTo(RolesAnywhereTestMaterial.CA_PEM))
                .body("trustAnchor.enabled", is(true))
                .body("trustAnchor.crlDpRevocationEnabled", is(false))
                .body("trustAnchor.createdAt", matchesPattern(TIMESTAMP))
                .body("trustAnchor.updatedAt", matchesPattern(TIMESTAMP))
                .body("trustAnchor.notificationSettings", hasSize(2))
                .body("trustAnchor.notificationSettings[0].event", equalTo("CA_CERTIFICATE_EXPIRY"))
                .body("trustAnchor.notificationSettings[0].threshold", equalTo(45))
                .body("trustAnchor.notificationSettings[0].configuredBy", equalTo("rolesanywhere.amazonaws.com"))
                .body("trustAnchor.aliasName", equalTo("it/anchor"))
                .body("trustAnchor.aliasArn", equalTo(ARN_PREFIX + "alias/trust-anchor/it/anchor"))
                .extract().response();
        String id = created.path("trustAnchor.trustAnchorId");
        String arn = created.path("trustAnchor.trustAnchorArn");
        try {
            rolesAnywhere().when().get("/trustanchor/" + id)
                    .then().statusCode(200).body("trustAnchor.trustAnchorArn", equalTo(arn));
            rolesAnywhere().when().get("/trustanchor/" + arn)
                    .then().statusCode(200).body("trustAnchor.trustAnchorId", equalTo(id));
            rolesAnywhere().when().get("/trustanchor/" + ARN_PREFIX + "alias/trust-anchor/it/anchor")
                    .then().statusCode(200).body("trustAnchor.trustAnchorId", equalTo(id));

            rolesAnywhere()
                    .body("{\"name\":\"it-anchor-renamed\",\"crlDpRevocationEnabled\":true,\"aliasName\":\"it-renamed\"}")
            .when()
                    .patch("/trustanchor/" + arn)
            .then()
                    .statusCode(200)
                    .body("trustAnchor.name", equalTo("it-anchor-renamed"))
                    .body("trustAnchor.crlDpRevocationEnabled", is(true))
                    .body("trustAnchor.aliasName", equalTo("it-renamed"))
                    .body("trustAnchor.previousAliasName", equalTo("it/anchor"));

            rolesAnywhere().when().post("/trustanchor/" + id + "/disable")
                    .then().statusCode(200).body("trustAnchor.enabled", is(false));
            rolesAnywhere().when().post("/trustanchor/" + arn + "/enable")
                    .then().statusCode(200).body("trustAnchor.enabled", is(true));
            rolesAnywhere().when().get("/trustanchors")
                    .then().statusCode(200).body("trustAnchors.trustAnchorId", hasItem(id));
        } finally {
            rolesAnywhere().when().delete("/trustanchor/" + id)
                    .then().statusCode(200).body("trustAnchor.trustAnchorId", equalTo(id));
        }
        rolesAnywhere().when().get("/trustanchor/" + id)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void notificationSettingsArePutAndReset() {
        String id = createAnchor("it-notifications");
        try {
            rolesAnywhere()
                    .body("{\"trustAnchorId\":\"" + id + "\",\"notificationSettings\":[{\"enabled\":false,"
                            + "\"event\":\"END_ENTITY_CERTIFICATE_EXPIRY\",\"threshold\":30,\"channel\":\"ALL\"}]}")
            .when()
                    .patch("/put-notifications-settings")
            .then()
                    .statusCode(200)
                    .body("trustAnchor.notificationSettings", hasSize(2))
                    .body("trustAnchor.notificationSettings[1].enabled", is(false))
                    .body("trustAnchor.notificationSettings[1].threshold", equalTo(30))
                    .body("trustAnchor.notificationSettings[1].configuredBy", equalTo(ACCOUNT_ID));

            rolesAnywhere()
                    .body("{\"trustAnchorId\":\"" + id + "\",\"notificationSettingKeys\":[{"
                            + "\"event\":\"END_ENTITY_CERTIFICATE_EXPIRY\",\"channel\":\"ALL\"}]}")
            .when()
                    .patch("/reset-notifications-settings")
            .then()
                    .statusCode(200)
                    .body("trustAnchor.notificationSettings[1].enabled", is(true))
                    .body("trustAnchor.notificationSettings[1].threshold", equalTo(45))
                    .body("trustAnchor.notificationSettings[1].configuredBy",
                            equalTo("rolesanywhere.amazonaws.com"));
        } finally {
            rolesAnywhere().when().delete("/trustanchor/" + id).then().statusCode(200);
        }
    }

    @Test
    void profileRoundTripsIncludingAttributeMappings() {
        Response created = rolesAnywhere()
                .body("{\"name\":\"it-profile\",\"roleArns\":[\"" + ROLE_ARN + "\"],\"durationSeconds\":1800,"
                        + "\"aliasName\":\"it-profile\",\"tipConfiguration\":{\"status\":\"DISABLED\"}}")
        .when()
                .post("/profiles")
        .then()
                .statusCode(201)
                .body("profile.profileArn", startsWith(ARN_PREFIX + "profile/"))
                .body("profile.name", equalTo("it-profile"))
                .body("profile.roleArns", contains(ROLE_ARN))
                .body("profile.managedPolicyArns", hasSize(0))
                .body("profile.durationSeconds", equalTo(1800))
                .body("profile.enabled", is(true))
                .body("profile.requireInstanceProperties", is(false))
                .body("profile.acceptRoleSessionName", is(false))
                .body("profile.createdBy", equalTo("arn:aws:iam::" + ACCOUNT_ID + ":root"))
                .body("profile.createdAt", matchesPattern(TIMESTAMP))
                .body("profile.attributeMappings.certificateField", contains("x509Subject", "x509Issuer", "x509SAN"))
                .body("profile.attributeMappings[2].mappingRules.specifier", contains("Name/DNS", "Name/URI"))
                .body("profile.tipConfiguration.status", equalTo("DISABLED"))
                .body("profile.aliasArn", equalTo(ARN_PREFIX + "alias/profile/it-profile"))
                .extract().response();
        String id = created.path("profile.profileId");
        String arn = created.path("profile.profileArn");
        try {
            rolesAnywhere()
                    .body("{\"certificateField\":\"x509Subject\",\"mappingRules\":[{\"specifier\":\"CN\"},"
                            + "{\"specifier\":\"OU\"}]}")
            .when()
                    .put("/profiles/" + arn + "/mappings")
            .then()
                    .statusCode(200)
                    .body("profile.attributeMappings[0].mappingRules.specifier", contains("CN", "OU"));

            rolesAnywhere()
                    .queryParam("certificateField", "x509Subject")
                    .queryParam("specifiers", "OU")
            .when()
                    .delete("/profiles/" + id + "/mappings")
            .then()
                    .statusCode(200)
                    .body("profile.attributeMappings[0].mappingRules.specifier", contains("CN"));

            rolesAnywhere()
                    .queryParam("certificateField", "x509SAN")
            .when()
                    .delete("/profiles/" + id + "/mappings")
            .then()
                    .statusCode(200)
                    .body("profile.attributeMappings.certificateField", contains("x509Subject", "x509Issuer"));

            rolesAnywhere()
                    .body("{\"durationSeconds\":7200,\"managedPolicyArns\":[\"arn:aws:iam::aws:policy/ReadOnlyAccess\"]}")
            .when()
                    .patch("/profile/" + ARN_PREFIX + "alias/profile/it-profile")
            .then()
                    .statusCode(200)
                    .body("profile.durationSeconds", equalTo(7200))
                    .body("profile.managedPolicyArns", contains("arn:aws:iam::aws:policy/ReadOnlyAccess"));

            rolesAnywhere().when().post("/profile/" + id + "/disable")
                    .then().statusCode(200).body("profile.enabled", is(false));
            rolesAnywhere().when().post("/profile/" + id + "/enable")
                    .then().statusCode(200).body("profile.enabled", is(true));
            rolesAnywhere().when().get("/profile/" + arn)
                    .then().statusCode(200).body("profile.profileId", equalTo(id));
        } finally {
            rolesAnywhere().when().delete("/profile/" + id)
                    .then().statusCode(200).body("profile.profileId", equalTo(id));
        }
    }

    @Test
    void crlRoundTripsAndGoesWithItsTrustAnchor() {
        String anchorId = createAnchor("it-crl-anchor");
        String anchorArn = ARN_PREFIX + "trust-anchor/" + anchorId;
        Response imported = rolesAnywhere()
                .body("{\"name\":\"it-crl\",\"crlData\":\"" + RolesAnywhereTestMaterial.crlData()
                        + "\",\"trustAnchorArn\":\"" + anchorArn + "\"}")
        .when()
                .post("/crls")
        .then()
                .statusCode(201)
                .body("crl.crlArn", startsWith(ARN_PREFIX + "crl/"))
                .body("crl.name", equalTo("it-crl"))
                .body("crl.crlData", equalTo(RolesAnywhereTestMaterial.crlData()))
                .body("crl.trustAnchorArn", equalTo(anchorArn))
                .body("crl.enabled", is(true))
                .body("crl.createdAt", matchesPattern(TIMESTAMP))
                .extract().response();
        String crlId = imported.path("crl.crlId");
        String crlArn = imported.path("crl.crlArn");

        rolesAnywhere().when().post("/crl/" + crlArn + "/disable")
                .then().statusCode(200).body("crl.enabled", is(false));
        rolesAnywhere().body("{\"name\":\"it-crl-renamed\",\"aliasName\":\"it-crl\"}")
                .when().patch("/crl/" + crlId)
                .then().statusCode(200).body("crl.name", equalTo("it-crl-renamed"))
                .body("crl.aliasArn", equalTo(ARN_PREFIX + "alias/crl/it-crl"));
        rolesAnywhere().when().get("/crls").then().statusCode(200).body("crls.crlId", hasItem(crlId));

        rolesAnywhere().when().delete("/trustanchor/" + anchorId).then().statusCode(200);
        rolesAnywhere().when().get("/crl/" + crlId)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listsPageWithPageSizeAndNextToken() {
        String account = "724681000042";
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(rolesAnywhere(account)
                    .body("{\"name\":\"page-" + i + "\",\"roleArns\":[\"arn:aws:iam::" + account + ":role/r\"]}")
                    .when().post("/profiles")
                    .then().statusCode(201).extract().path("profile.profileId"));
        }
        try {
            Response first = rolesAnywhere(account).queryParam("pageSize", 2).when().get("/profiles")
                    .then().statusCode(200)
                    .body("profiles", hasSize(2))
                    .body("nextToken", notNullValue())
                    .extract().response();
            rolesAnywhere(account).queryParam("pageSize", 2).queryParam("nextToken", (String) first.path("nextToken"))
                    .when().get("/profiles")
                    .then().statusCode(200)
                    .body("profiles.profileId", contains(ids.get(2)))
                    .body("nextToken", nullValue());
            assertThat(first.path("profiles.profileId"), equalTo(ids.subList(0, 2)));
            rolesAnywhere(account).queryParam("pageSize", 0).when().get("/profiles")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
            rolesAnywhere(account).when().get("/subjects")
                    .then().statusCode(200).body("subjects", hasSize(0));
        } finally {
            for (String id : ids) {
                rolesAnywhere(account).when().delete("/profile/" + id).then().statusCode(200);
            }
        }
    }

    @Test
    void tagsAreAddedListedAndRemoved() {
        String id = createAnchor("it-tags");
        String arn = ARN_PREFIX + "trust-anchor/" + id;
        try {
            rolesAnywhere()
                    .body("{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"identity\"},"
                            + "{\"key\":\"env\",\"value\":\"it\"}]}")
            .when()
                    .post("/TagResource")
            .then()
                    .statusCode(201);

            rolesAnywhere().queryParam("resourceArn", arn).when().get("/ListTagsForResource")
                    .then().statusCode(200)
                    .body("tags.key", contains("team", "env"))
                    .body("tags.value", contains("identity", "it"));

            rolesAnywhere().body("{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"env\"]}")
                    .when().post("/UntagResource").then().statusCode(200);

            rolesAnywhere().queryParam("resourceArn", arn).when().get("/ListTagsForResource")
                    .then().statusCode(200).body("tags.key", contains("team"));
        } finally {
            rolesAnywhere().when().delete("/trustanchor/" + id).then().statusCode(200);
        }
    }

    @Test
    void errorsUseTheRestJsonErrorShape() {
        rolesAnywhere()
                .body("{\"name\":\"missing-source\"}")
        .when()
                .post("/trustanchors")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at 'source' failed to satisfy "
                        + "constraint: Member must not be null"));

        rolesAnywhere().when().get("/profile/00000000-0000-0000-0000-000000000000")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        rolesAnywhere()
                .body("{\"name\":\"pca\",\"source\":{\"sourceType\":\"AWS_ACM_PCA\",\"sourceData\":{\"acmPcaArn\":"
                        + "\"arn:aws:acm-pca:us-east-1:" + ACCOUNT_ID + ":certificate-authority/x\"}}}")
        .when()
                .post("/trustanchors")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("does not emulate AWS Private Certificate Authority"));
    }

    @Test
    void aRequestSignedForAnotherServiceKeepsItsPathAndDoesNotReachRolesAnywhere() {
        given()
                .contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID
                        + "/20260101/us-east-1/s3/aws4_request")
        .when()
                .get("/trustanchors")
        .then()
                .statusCode(404)
                .body(not(containsString("trustAnchors")));
    }

    @Test
    void theSdkClientRoundTripsATrustAnchorAProfileACrlAndTags() {
        try (RolesAnywhereClient client = client()) {
            TrustAnchorDetail anchor = client.createTrustAnchor(request -> request.name("sdk-anchor")
                    .enabled(true)
                    .source(source -> source.sourceType(TrustAnchorType.CERTIFICATE_BUNDLE)
                            .sourceData(data -> data.x509CertificateData(RolesAnywhereTestMaterial.CA_PEM)))
                    .tags(Tag.builder().key("team").value("sdk").build())).trustAnchor();
            try {
                assertThat(anchor.trustAnchorArn(), startsWith(ARN_PREFIX + "trust-anchor/"));
                assertThat(anchor.createdAt(), notNullValue());
                assertThat(anchor.createdAt().isAfter(Instant.parse("2020-01-01T00:00:00Z")), is(true));
                List<NotificationEvent> events = new ArrayList<>();
                for (NotificationSettingDetail setting : anchor.notificationSettings()) {
                    events.add(setting.event());
                }
                assertThat(events, contains(NotificationEvent.CA_CERTIFICATE_EXPIRY,
                        NotificationEvent.END_ENTITY_CERTIFICATE_EXPIRY));
                assertThat(client.getTrustAnchor(request -> request.trustAnchorId(anchor.trustAnchorArn()))
                        .trustAnchor().name(), equalTo("sdk-anchor"));
                assertThat(client.listTrustAnchorsPaginator(request -> request.pageSize(1)).trustAnchors().stream()
                        .map(TrustAnchorDetail::trustAnchorId).toList(), hasItem(anchor.trustAnchorId()));
                assertThat(client.listTagsForResource(request -> request.resourceArn(anchor.trustAnchorArn()))
                        .tags().get(0).value(), equalTo("sdk"));

                ProfileDetail profile = client.createProfile(request -> request.name("sdk-profile")
                        .roleArns(ROLE_ARN).durationSeconds(1200)).profile();
                assertThat(profile.durationSeconds(), equalTo(1200));
                assertThat(profile.updatedAt(), notNullValue());
                ProfileDetail mapped = client.putAttributeMapping(request -> request.profileId(profile.profileArn())
                        .certificateField(CertificateField.X509_ISSUER)
                        .mappingRules(rule -> rule.specifier("CN"))).profile();
                AttributeMapping issuer = mapped.attributeMappings().get(1);
                assertThat(issuer.certificateField(), equalTo(CertificateField.X509_ISSUER));
                assertThat(issuer.mappingRules(), hasSize(1));
                client.deleteProfile(request -> request.profileId(profile.profileId()));

                CrlDetail crl = client.importCrl(request -> request.name("sdk-crl")
                        .trustAnchorArn(anchor.trustAnchorArn())
                        .crlData(SdkBytes.fromString(RolesAnywhereTestMaterial.CRL_PEM, StandardCharsets.US_ASCII)))
                        .crl();
                assertThat(crl.crlData().asString(StandardCharsets.US_ASCII),
                        equalTo(RolesAnywhereTestMaterial.CRL_PEM));
                assertThat(client.getCrl(request -> request.crlId(crl.crlArn())).crl().name(), equalTo("sdk-crl"));

                ValidationException invalid = assertThrows(ValidationException.class,
                        () -> client.createProfile(request -> request.name("bad").roleArns(ROLE_ARN)
                                .durationSeconds(60)));
                assertThat(invalid.awsErrorDetails().errorMessage(), containsString("durationSeconds"));
            } finally {
                client.deleteTrustAnchor(request -> request.trustAnchorId(anchor.trustAnchorId()));
            }
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getTrustAnchor(request -> request.trustAnchorId(anchor.trustAnchorId())));
        }
    }

    private String createAnchor(String name) {
        return rolesAnywhere().body(anchorBody(name).toString())
                .when().post("/trustanchors")
                .then().statusCode(201).extract().path("trustAnchor.trustAnchorId");
    }

    private ObjectNode anchorBody(String name) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        ObjectNode source = body.putObject("source");
        source.put("sourceType", "CERTIFICATE_BUNDLE");
        source.putObject("sourceData").put("x509CertificateData", RolesAnywhereTestMaterial.CA_PEM);
        return body;
    }

    private RolesAnywhereClient client() {
        return RolesAnywhereClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT_ID, "test")))
                .build();
    }

    private static RequestSpecification rolesAnywhere() {
        return rolesAnywhere(ACCOUNT_ID);
    }

    private static RequestSpecification rolesAnywhere(String accountId) {
        return given()
                .contentType("application/json")
                .urlEncodingEnabled(false)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accountId
                        + "/20260101/" + REGION + "/rolesanywhere/aws4_request");
    }
}
