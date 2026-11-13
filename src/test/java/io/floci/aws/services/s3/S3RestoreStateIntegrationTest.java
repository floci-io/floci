package io.floci.aws.services.s3;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.MutableClock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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

            given().body(RESTORE_REQUEST).when().post(olderPath + "&restore").then().statusCode(202);
            given().when().head(olderPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo("ongoing-request=\"true\""));
            given().when().get(olderPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo("ongoing-request=\"true\""));
            given().when().head(currentPath).then().statusCode(200).header("x-amz-restore", nullValue());

            given().body(RESTORE_REQUEST).when().post(currentPath + "&restore").then().statusCode(202);
            given().when().head(currentPath).then().statusCode(200)
                    .header("x-amz-restore", equalTo("ongoing-request=\"true\""));
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
            given().header("x-amz-optional-object-attributes", "RestoreStatus")
                    .queryParam("list-type", "2").when().get(fixture.bucketPath()).then().statusCode(200)
                    .body(containsString("<IsRestoreInProgress>false</IsRestoreInProgress>"))
                    .body(containsString("<RestoreExpiryDate>2026-01-03T00:00:00Z</RestoreExpiryDate>"));
            given().body(RESTORE_REQUEST).when().post(olderPath + "&restore").then().statusCode(200);
            given().body(RESTORE_REQUEST).when().post(currentPath + "&restore").then().statusCode(200);
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
