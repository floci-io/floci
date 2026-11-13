package io.floci.aws.services.s3;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.MutableClock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(S3RestoreStateIntegrationTest.DelayedRestoreProfile.class)
class S3RestoreStateIntegrationTest {
    private static final String RESTORE_REQUEST = "<RestoreRequest><Days>1</Days></RestoreRequest>";
    private static final String ARCHIVED_OBJECT_ERROR_MESSAGE =
            "The operation is not valid for the object's storage class";
    private static final String COMPLETED_RESTORE_HEADER =
            "ongoing-request=\"false\", expiry-date=\"Sat, 03 Jan 2026 00:00:00 GMT\"";

    @Inject
    MutableClock clock;

    public static final class DelayedRestoreProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.restore-completion-delay-seconds", "60");
        }
    }

    @BeforeEach
    void resetClockBeforeTest() {
        clock.reset();
    }

    @AfterEach
    void resetClockAfterTest() {
        clock.reset();
    }

    @Test
    void restoreStateFollowsTheVersionThroughHeadGetAndList() {
        try (Fixture fixture = new Fixture()) {
            String olderPath = fixture.path() + "?versionId=" + fixture.olderVersion();
            String currentPath = fixture.path() + "?versionId=" + fixture.currentVersion();

            given().when().head(olderPath).then().statusCode(200).header("x-amz-restore", nullValue());
            given().when().head(currentPath).then().statusCode(200).header("x-amz-restore", nullValue());
            given().when().get(olderPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"))
                    .body("Error.Message", equalTo(ARCHIVED_OBJECT_ERROR_MESSAGE))
                    .body("Error.StorageClass", equalTo("GLACIER"));
            given().when().get(currentPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
            given().when().get(fixture.path()).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));

            given().body(RESTORE_REQUEST).when().post(olderPath + "&restore").then().statusCode(202);
            given().when().head(olderPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo("ongoing-request=\"true\""));
            given().when().get(olderPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
            given().when().head(currentPath).then().statusCode(200).header("x-amz-restore", nullValue());

            given().body(RESTORE_REQUEST).when().post(currentPath + "&restore").then().statusCode(202);
            given().when().head(currentPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo("ongoing-request=\"true\""));
            given().header("Range", "bytes=0-1").when().get(currentPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
            given().when().get(currentPath + "&partNumber=1").then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
            given().header("x-amz-optional-object-attributes", "RestoreStatus")
                    .queryParam("list-type", "2").when().get(fixture.bucketPath()).then().statusCode(200)
                    .body(containsString("<IsRestoreInProgress>true</IsRestoreInProgress>"));
            given().queryParam("list-type", "2").when().get(fixture.bucketPath()).then().statusCode(200)
                    .body(not(containsString("<RestoreStatus>")));
            given().header("x-amz-optional-object-attributes", "RestoreStatus")
                    .when().get(fixture.bucketPath()).then().statusCode(200)
                    .body(containsString("<IsRestoreInProgress>true</IsRestoreInProgress>"));
            given().when().get(fixture.bucketPath()).then().statusCode(200)
                    .body(not(containsString("<RestoreStatus>")));

            clock.advance(Duration.ofSeconds(61));
            given().when().head(currentPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo(COMPLETED_RESTORE_HEADER));
            given().when().get(currentPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo(COMPLETED_RESTORE_HEADER))
                    .body(equalTo("current"));
            given().when().get(olderPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo(COMPLETED_RESTORE_HEADER))
                    .body(equalTo("older"));
            given().header("Range", "bytes=0-1").when().get(currentPath).then().statusCode(206)
                    .body(equalTo("cu"));
            given().header("x-amz-optional-object-attributes", "RestoreStatus")
                    .queryParam("list-type", "2").when().get(fixture.bucketPath()).then().statusCode(200)
                    .body(containsString("<IsRestoreInProgress>false</IsRestoreInProgress>"))
                    .body(containsString("<RestoreExpiryDate>2026-01-03T00:00:00Z</RestoreExpiryDate>"));
            given().body(RESTORE_REQUEST).when().post(olderPath + "&restore").then().statusCode(200);
            given().body(RESTORE_REQUEST).when().post(currentPath + "&restore").then().statusCode(200);

            clock.advance(Duration.ofDays(2));
            given().when().head(olderPath).then().statusCode(200).header("x-amz-restore", nullValue());
            given().when().get(olderPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
            given().when().get(currentPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
        }
    }

    @Test
    void restoringOneVersionDoesNotUnlockAnother() {
        try (Fixture fixture = new Fixture()) {
            String olderPath = fixture.path() + "?versionId=" + fixture.olderVersion();
            String currentPath = fixture.path() + "?versionId=" + fixture.currentVersion();

            given().body(RESTORE_REQUEST).when().post(olderPath + "&restore").then().statusCode(202);
            clock.advance(Duration.ofSeconds(61));

            given().when().get(olderPath).then().statusCode(200).body(equalTo("older"));
            given().when().get(currentPath).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
            given().when().get(fixture.path()).then().statusCode(403)
                    .body("Error.Code", equalTo("InvalidObjectState"));
        }
    }

    @Test
    void deepArchiveGetObjectRequiresCompletedRestore() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.bucketPath() + "/deep.txt";
            String version = given().header("x-amz-storage-class", "DEEP_ARCHIVE").body("deep")
                    .when().put(path).then().statusCode(200).extract().header("x-amz-version-id");
            String versionedPath = path + "?versionId=" + version;
            try {
                given().when().head(versionedPath).then().statusCode(200);
                given().when().get(versionedPath).then().statusCode(403)
                        .body("Error.Code", equalTo("InvalidObjectState"))
                        .body("Error.Message", equalTo(ARCHIVED_OBJECT_ERROR_MESSAGE))
                        .body("Error.StorageClass", equalTo("DEEP_ARCHIVE"));

                given().body(RESTORE_REQUEST).when().post(versionedPath + "&restore").then().statusCode(202);
                given().when().get(versionedPath).then().statusCode(403)
                        .body("Error.Code", equalTo("InvalidObjectState"));

                clock.advance(Duration.ofSeconds(61));
                given().when().get(versionedPath).then().statusCode(200).body(equalTo("deep"));

                clock.advance(Duration.ofDays(2));
                given().when().get(versionedPath).then().statusCode(403)
                        .body("Error.Code", equalTo("InvalidObjectState"));
            } finally {
                given().when().delete(versionedPath).then().statusCode(204);
            }
        }
    }

    @Test
    void glacierInstantRetrievalDoesNotRequireRestore() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.bucketPath() + "/instant.txt";
            String version = given().header("x-amz-storage-class", "GLACIER_IR").body("instant")
                    .when().put(path).then().statusCode(200).extract().header("x-amz-version-id");
            try {
                given().when().get(path + "?versionId=" + version)
                        .then().statusCode(200).body(equalTo("instant"));
            } finally {
                given().when().delete(path + "?versionId=" + version).then().statusCode(204);
            }
        }
    }

    @Test
    void websiteDoesNotServeArchivedIndexOrErrorDocumentUntilRestored() {
        try (Fixture fixture = new Fixture()) {
            String indexPath = fixture.bucketPath() + "/index.html";
            String errorPath = fixture.bucketPath() + "/error.html";
            String websiteHost = fixture.bucket + ".s3-website-us-east-1.localhost:" + RestAssured.port;
            String websiteConfiguration = "<WebsiteConfiguration><IndexDocument><Suffix>index.html</Suffix>"
                    + "</IndexDocument><ErrorDocument><Key>error.html</Key></ErrorDocument>"
                    + "</WebsiteConfiguration>";
            given().body(websiteConfiguration).when().put(fixture.bucketPath() + "?website")
                    .then().statusCode(200);

            String indexVersion = null;
            String errorVersion = null;
            try {
                indexVersion = given().header("x-amz-storage-class", "GLACIER").body("archived index")
                        .when().put(indexPath).then().statusCode(200).extract().header("x-amz-version-id");
                errorVersion = given().header("x-amz-storage-class", "DEEP_ARCHIVE").body("archived error")
                        .when().put(errorPath).then().statusCode(200).extract().header("x-amz-version-id");

                given().header("Host", websiteHost).when().get("/missing-page").then().statusCode(404)
                        .body(not(containsString("archived error")));
                given().header("Host", websiteHost).when().head("/").then().statusCode(200);
                given().header("Host", websiteHost).when().head("/index.html").then().statusCode(200);
                given().header("Host", websiteHost).when().get("/index.html").then().statusCode(403)
                        .header("Content-Type", containsString("text/html"))
                        .body(not(containsString("archived index")))
                        .body(not(containsString("archived error")));
                given().header("Host", websiteHost).when().get("/").then().statusCode(not(equalTo(200)))
                        .body(not(containsString("archived index")));

                given().body(RESTORE_REQUEST).when().post(indexPath + "?versionId=" + indexVersion + "&restore")
                        .then().statusCode(202);
                given().body(RESTORE_REQUEST).when().post(errorPath + "?versionId=" + errorVersion + "&restore")
                        .then().statusCode(202);
                given().header("Host", websiteHost).when().get("/").then().statusCode(not(equalTo(200)))
                        .body(not(containsString("archived index")));
                given().header("Host", websiteHost).when().get("/missing-page").then().statusCode(404)
                        .body(not(containsString("archived error")));

                clock.advance(Duration.ofSeconds(61));
                given().header("Host", websiteHost).when().get("/").then().statusCode(200)
                        .body(equalTo("archived index"));
                given().header("Host", websiteHost).when().get("/missing-page").then().statusCode(404)
                        .body(equalTo("archived error"));

                clock.advance(Duration.ofDays(2));
                given().header("Host", websiteHost).when().get("/").then().statusCode(not(equalTo(200)))
                        .body(not(containsString("archived index")));
                given().header("Host", websiteHost).when().get("/missing-page").then().statusCode(404)
                        .body(not(containsString("archived error")));
            } finally {
                if (errorVersion != null) {
                    given().when().delete(errorPath + "?versionId=" + errorVersion).then().statusCode(204);
                }
                if (indexVersion != null) {
                    given().when().delete(indexPath + "?versionId=" + indexVersion).then().statusCode(204);
                }
            }
        }
    }

    @Test
    void repeatedRestoreWhileInProgressIsRejected() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.path() + "?versionId=" + fixture.currentVersion() + "&restore";
            given().body(RESTORE_REQUEST).when().post(path).then().statusCode(202);
            given().body(RESTORE_REQUEST).when().post(path).then().statusCode(409)
                    .body("Error.Code", equalTo("RestoreAlreadyInProgress"));
        }
    }

    @Test
    void listVersionsIncludesRestoreStatusOnlyWhenRequested() {
        try (Fixture fixture = new Fixture()) {
            String olderPath = fixture.path() + "?versionId=" + fixture.olderVersion();
            given().body(RESTORE_REQUEST).when().post(olderPath + "&restore").then().statusCode(202);
            String deleteMarker = given().when().delete(fixture.path()).then().statusCode(204)
                    .extract().header("x-amz-version-id");
            fixture.trackVersion(deleteMarker);

            String withoutAttribute = given().when().get(fixture.bucketPath() + "?versions")
                    .then().statusCode(200).extract().asString();
            assertEquals(0, XmlParser.parseDocumentElementTree(withoutAttribute).children().stream()
                    .filter(child -> "Version".equals(child.name()))
                    .filter(version -> version.child("RestoreStatus") != null).count());

            String withAttribute = given().header("x-amz-optional-object-attributes", "RestoreStatus")
                    .when().get(fixture.bucketPath() + "?versions")
                    .then().statusCode(200).extract().asString();
            XmlParser.XmlElement result = XmlParser.parseDocumentElementTree(withAttribute);
            assertEquals(1, result.count("DeleteMarker"));
            assertNull(result.child("DeleteMarker").child("RestoreStatus"));
            assertEquals(2, result.count("Version"));
            for (XmlParser.XmlElement version : result.children()) {
                if (!"Version".equals(version.name())) {
                    continue;
                }
                XmlParser.XmlElement status = version.child("RestoreStatus");
                if (fixture.olderVersion().equals(version.child("VersionId").text())) {
                    assertNotNull(status);
                    assertEquals("true", status.child("IsRestoreInProgress").text());
                    assertNull(status.child("RestoreExpiryDate"));
                } else {
                    assertEquals(fixture.currentVersion(), version.child("VersionId").text());
                    assertNull(status);
                }
            }
        }
    }

    @Test
    void deepArchiveRejectsExpeditedRestoreWithoutStateChange() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.bucketPath() + "/deep.txt";
            String version = given().header("x-amz-storage-class", "DEEP_ARCHIVE").body("deep")
                    .when().put(path).then().statusCode(200).extract().header("x-amz-version-id");
            try {
                for (String request : List.of(
                        "<RestoreRequest><Days>1</Days><Tier>Expedited</Tier></RestoreRequest>",
                        "<RestoreRequest><Days>1</Days><GlacierJobParameters><Tier>Expedited</Tier>"
                                + "</GlacierJobParameters></RestoreRequest>")) {
                    given().body(request).when().post(path + "?versionId=" + version + "&restore")
                            .then().statusCode(400);
                    given().when().head(path + "?versionId=" + version).then().statusCode(200)
                            .header("x-amz-restore", nullValue());
                }
            } finally {
                given().when().delete(path + "?versionId=" + version).then().statusCode(204);
            }
        }
    }

    @Test
    void mixedTextInRestoreContainersIsRejectedWithoutStateChange() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.path() + "?versionId=" + fixture.currentVersion();
            for (String request : List.of(
                    "<RestoreRequest>junk<Days>1</Days></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><GlacierJobParameters>junk<Tier>Standard</Tier>"
                            + "</GlacierJobParameters></RestoreRequest>")) {
                given().body(request).when().post(path + "&restore").then().statusCode(400)
                        .body("Error.Code", equalTo("MalformedXML"));
                given().when().head(path).then().statusCode(200).header("x-amz-restore", nullValue());
            }
        }
    }

    @Test
    void completedRestoreHeaderPadsSingleDigitDay() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.path() + "?versionId=" + fixture.currentVersion();
            given().body(RESTORE_REQUEST).when().post(path + "&restore").then().statusCode(202);
            clock.advance(Duration.ofSeconds(61));
            given().when().head(path).then().statusCode(200)
                    .header("x-amz-restore", equalTo(COMPLETED_RESTORE_HEADER));
        }
    }

    @Test
    void overwritingAndPromotingVersionsKeepRestoreStateWithTheVersion() {
        try (Fixture fixture = new Fixture()) {
            String currentPath = fixture.path() + "?versionId=" + fixture.currentVersion();
            given().body(RESTORE_REQUEST).when().post(currentPath + "&restore").then().statusCode(202);
            given().when().head(currentPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo("ongoing-request=\"true\""));

            String replacement = fixture.put("replacement");
            given().when().head(fixture.path()).then().statusCode(200).header("x-amz-restore", nullValue());
            given().when().head(currentPath).then().statusCode(200).header("x-amz-restore", notNullValue());

            given().when().delete(fixture.path() + "?versionId=" + replacement).then().statusCode(204);
            fixture.forget(replacement);
            given().when().head(fixture.path()).then().statusCode(200).header("x-amz-restore", notNullValue());
        }
    }

    @Test
    void invalidRequestsDoNotCreateRestoreState() {
        try (Fixture fixture = new Fixture()) {
            String path = fixture.path() + "?versionId=" + fixture.currentVersion();
            for (String invalid : List.of("<RestoreRequest/>",
                    "<RestoreRequest><Days>0</Days></RestoreRequest>",
                    "<RestoreRequest><Days>invalid</Days></RestoreRequest>",
                    "<RestoreRequest><Nested><Days>1</Days></Nested></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Days>2</Days></RestoreRequest>",
                    "<RestoreRequest><Days>2147483648</Days></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Unknown/></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Tier>Standard</Tier><Tier>Standard</Tier></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Tier>Invalid</Tier></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Tier><Bogus/></Tier></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><GlacierJobParameters/></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><GlacierJobParameters><Tier>Invalid</Tier></GlacierJobParameters></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><GlacierJobParameters><Bogus/></GlacierJobParameters></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Description><Bogus/></Description></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><Type>SELECT</Type></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days><OutputLocation/></RestoreRequest>",
                    "<RestoreRequest><Days>1</Days>")) {
                given().body(invalid).when().post(path + "&restore").then().statusCode(400)
                        .body("Error.Code", equalTo("MalformedXML"));
            }
            given().when().head(path).then().statusCode(200).header("x-amz-restore", nullValue());

            given().body(RESTORE_REQUEST).when().post(fixture.bucketPath() + "/missing?restore")
                    .then().statusCode(404).body("Error.Code", equalTo("NoSuchKey"));

            String ordinaryVersion = given().body("ordinary")
                    .when().put(fixture.bucketPath() + "/ordinary.txt").then().statusCode(200)
                    .extract().header("x-amz-version-id");
            given().body(RESTORE_REQUEST).when().post(fixture.bucketPath() + "/ordinary.txt?restore")
                    .then().statusCode(403).body("Error.Code", equalTo("ObjectAlreadyInActiveTierError"));
            given().when().head(fixture.bucketPath() + "/ordinary.txt").then().statusCode(200)
                    .header("x-amz-restore", nullValue());
            given().when().get(fixture.bucketPath() + "/ordinary.txt")
                    .then().statusCode(200).body(equalTo("ordinary"));
            given().when().delete(fixture.bucketPath() + "/ordinary.txt?versionId=" + ordinaryVersion)
                    .then().statusCode(204);
        }
    }

    @Test
    void validOptionalRestoreTiersAreAccepted() {
        try (Fixture fixture = new Fixture()) {
            given().body("<RestoreRequest><Days>1</Days><Tier>Standard</Tier>"
                    + "<Description>test restore</Description></RestoreRequest>")
                    .when().post(fixture.path() + "?versionId=" + fixture.olderVersion() + "&restore")
                    .then().statusCode(202);
            given().body("<RestoreRequest><Days>1</Days><GlacierJobParameters><Tier>Bulk</Tier>"
                    + "</GlacierJobParameters></RestoreRequest>")
                    .when().post(fixture.path() + "?versionId=" + fixture.currentVersion() + "&restore")
                    .then().statusCode(202);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String bucket = "restore-state-it-" + UUID.randomUUID().toString().substring(0, 8);
        private final String path = bucketPath() + "/archived.txt";
        private final List<String> versions = new ArrayList<>();
        private final String olderVersion;
        private final String currentVersion;

        private Fixture() {
            given().when().put(bucketPath()).then().statusCode(200);
            given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                    .when().put(bucketPath() + "?versioning").then().statusCode(200);
            olderVersion = put("older");
            currentVersion = put("current");
        }

        private String put(String body) {
            String version = given().header("x-amz-storage-class", "GLACIER").body(body)
                    .when().put(path).then().statusCode(200)
                    .header("x-amz-version-id", notNullValue())
                    .extract().header("x-amz-version-id");
            versions.add(version);
            return version;
        }

        private void forget(String version) { versions.remove(version); }

        private void trackVersion(String version) { versions.add(version); }

        private String bucketPath() { return "/" + bucket; }
        private String path() { return path; }
        private String olderVersion() { return olderVersion; }
        private String currentVersion() { return currentVersion; }

        @Override
        public void close() {
            for (int i = versions.size() - 1; i >= 0; i--) {
                given().when().delete(path + "?versionId=" + versions.get(i)).then().statusCode(204);
            }
            given().when().delete(bucketPath()).then().statusCode(204);
        }
    }
}
