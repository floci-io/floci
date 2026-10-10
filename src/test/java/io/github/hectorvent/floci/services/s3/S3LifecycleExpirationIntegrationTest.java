package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Lifecycle expiration as a client sees it: the {@code x-amz-expiration} header on objects a rule
 * matches, and the background sweep (every second in the test profile) removing what has expired.
 */
@QuarkusTest
class S3LifecycleExpirationIntegrationTest {

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    @Test
    void objectsMatchedByADaysRuleCarryTheRoundedExpiryDateAndRuleId() {
        String bucket = "lc-expiration-header";
        given().put("/" + bucket).then().statusCode(200);
        putLifecycle(bucket, rule("three days", "<Filter><Prefix>tmp/</Prefix></Filter>",
                "<Expiration><Days>3</Days></Expiration>"));

        Response put = given().body("hi").put("/" + bucket + "/tmp/a.txt");
        put.then().statusCode(200);
        Response head = given().head("/" + bucket + "/tmp/a.txt");
        head.then().statusCode(200);

        // Creation time plus three days, rounded up to the next midnight UTC.
        Instant created = ZonedDateTime.parse(head.header("Last-Modified"),
                DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        String expected = "expiry-date=\"" + HTTP_DATE.format(
                created.plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS))
                + "\", rule-id=\"three%20days\"";
        head.then().header("x-amz-expiration", equalTo(expected));
        put.then().header("x-amz-expiration", equalTo(expected));
        given().get("/" + bucket + "/tmp/a.txt").then()
                .statusCode(200)
                .header("x-amz-expiration", equalTo(expected));

        given().body("hi").put("/" + bucket + "/keep/b.txt").then()
                .statusCode(200)
                .header("x-amz-expiration", nullValue());
        given().head("/" + bucket + "/keep/b.txt").then()
                .statusCode(200)
                .header("x-amz-expiration", nullValue());
    }

    @Test
    void aDateRuleReportsThatDateAsTheExpiryDate() {
        String bucket = "lc-expiration-date-header";
        given().put("/" + bucket).then().statusCode(200);
        putLifecycle(bucket, rule("future-date", "<Filter><Prefix></Prefix></Filter>",
                "<Expiration><Date>2099-01-01T00:00:00Z</Date></Expiration>"));
        given().body("hi").put("/" + bucket + "/a.txt").then().statusCode(200);

        given().head("/" + bucket + "/a.txt").then()
                .statusCode(200)
                .header("x-amz-expiration",
                        equalTo("expiry-date=\"Thu, 01 Jan 2099 00:00:00 GMT\", rule-id=\"future-date\""));
    }

    @Test
    void theSweepDeletesObjectsPastTheirExpirationAndLeavesOthers() {
        String bucket = "lc-expiration-sweep";
        given().put("/" + bucket).then().statusCode(200);
        given().body("hi").put("/" + bucket + "/tmp/a.txt").then().statusCode(200);
        given().body("hi").put("/" + bucket + "/keep/b.txt").then().statusCode(200);
        putLifecycle(bucket, rule("past-date", "<Filter><Prefix>tmp/</Prefix></Filter>",
                "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>"));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).until(() ->
                given().head("/" + bucket + "/tmp/a.txt").statusCode() == 404);

        given().get("/" + bucket + "?list-type=2").then()
                .statusCode(200)
                .body(not(containsString("<Key>tmp/a.txt</Key>")))
                .body(containsString("<Key>keep/b.txt</Key>"));
    }

    @Test
    void theSweepPlacesADeleteMarkerInAVersionedBucket() {
        String bucket = "lc-expiration-versioned";
        given().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .put("/" + bucket + "?versioning").then().statusCode(200);
        String versionId = given().body("hi").put("/" + bucket + "/tmp/a.txt")
                .then().statusCode(200).extract().header("x-amz-version-id");
        putLifecycle(bucket, rule("past-date", "<Filter><Prefix>tmp/</Prefix></Filter>",
                "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>"));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).until(() ->
                given().head("/" + bucket + "/tmp/a.txt").statusCode() == 404);

        given().get("/" + bucket + "?versions").then()
                .statusCode(200)
                .body(containsString("<DeleteMarker>"))
                .body(containsString("<VersionId>" + versionId + "</VersionId>"));
        given().get("/" + bucket + "/tmp/a.txt?versionId=" + versionId).then().statusCode(200);
    }

    @Test
    void theSweepAppliesEachAccountsRulesToThatAccountsBucket() {
        String bucket = "lc-expiration-accounts";
        String withRule = "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        String withoutRule = "AWS4-HMAC-SHA256 Credential=000000000003/20260215/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        for (String auth : new String[] {withRule, withoutRule}) {
            given().header("Authorization", auth).put("/" + bucket).then().statusCode(200);
            given().header("Authorization", auth).body("hi").put("/" + bucket + "/a.txt").then().statusCode(200);
        }
        given()
            .header("Authorization", withRule)
            .contentType("application/xml")
            .body("<LifecycleConfiguration>" + rule("past-date", "<Filter><Prefix></Prefix></Filter>",
                    "<Expiration><Date>2020-01-01T00:00:00Z</Date></Expiration>") + "</LifecycleConfiguration>")
        .when()
            .put("/" + bucket + "?lifecycle")
        .then()
            .statusCode(200);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).until(() ->
                given().header("Authorization", withRule).head("/" + bucket + "/a.txt").statusCode() == 404);

        given().header("Authorization", withoutRule).head("/" + bucket + "/a.txt").then().statusCode(200);
    }

    private static String rule(String id, String filter, String actions) {
        return "<Rule><ID>" + id + "</ID>" + filter + "<Status>Enabled</Status>" + actions + "</Rule>";
    }

    private static void putLifecycle(String bucket, String rules) {
        given()
            .contentType("application/xml")
            .body("<LifecycleConfiguration>" + rules + "</LifecycleConfiguration>")
        .when()
            .put("/" + bucket + "?lifecycle")
        .then()
            .statusCode(200);
    }
}
