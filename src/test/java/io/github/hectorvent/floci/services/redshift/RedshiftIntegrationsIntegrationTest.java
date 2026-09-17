package io.github.hectorvent.floci.services.redshift;

import java.util.ArrayList;
import java.util.List;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Redshift zero-ETL integrations.
 *
 * <p>The response shape was captured from a live integration in us-west-2: {@code Status} is lower
 * case ({@code active}), {@code Errors} is present but empty on a healthy integration, and an
 * unknown {@code IntegrationArn} is {@code IntegrationNotFoundFault}. An account with no
 * integrations returns an empty list rather than an error.
 */
@QuarkusTest
class RedshiftIntegrationsIntegrationTest {

    private static final String SOURCE = "arn:aws:dynamodb:us-east-1:000000000000:table/keystone-main";
    private static final String TARGET =
            "arn:aws:redshift-serverless:us-east-1:000000000000:namespace/8445f0c7-d2b1-4c1c-916c-0eeaa68fd487";

    /**
     * The Authorization header is what routes a Query request to a service; without it the
     * emulator cannot tell which service the action belongs to.
     */
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260822/us-east-1/redshift/aws4_request";

    private static Response query(String... formParams) {
        RequestSpecification spec = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER)
                .formParam("Version", "2012-12-01");
        for (int i = 0; i < formParams.length; i += 2) {
            spec = spec.formParam(formParams[i], formParams[i + 1]);
        }
        return spec.when().post("/");
    }

    private static String createIntegration(String name) {
        return query("Action", "CreateIntegration", "IntegrationName", name,
                "SourceArn", SOURCE, "TargetArn", TARGET)
                .then().statusCode(200)
                .extract().body().asString();
    }

    private static String arnOf(String createResponseXml) {
        int start = createResponseXml.indexOf("<IntegrationArn>") + "<IntegrationArn>".length();
        return createResponseXml.substring(start, createResponseXml.indexOf("</IntegrationArn>"));
    }

    @Test
    void aCreatedIntegrationIsDescribed() {
        createIntegration("zetl-described");

        query("Action", "DescribeIntegrations")
                .then().statusCode(200)
                .body(containsString("zetl-described"))
                .body(containsString(SOURCE))
                .body(containsString(TARGET))
                // Lower case on real Redshift, not ACTIVE.
                .body(containsString("<Status>active</Status>"))
                .body(containsString("<Errors></Errors>"));
    }

    @Test
    void describingByArnReturnsOnlyThatIntegration() {
        String arn = arnOf(createIntegration("zetl-byarn"));
        createIntegration("zetl-other");

        query("Action", "DescribeIntegrations", "IntegrationArn", arn)
                .then().statusCode(200)
                .body(containsString("zetl-byarn"))
                .body(not(containsString("zetl-other")));
    }

    @Test
    void anUnknownArnIsNotFound() {
        query("Action", "DescribeIntegrations", "IntegrationArn",
                "arn:aws:redshift:us-east-1:000000000000:integration:00000000-0000-0000-0000-000000000000")
                .then().statusCode(404)
                .body(containsString("IntegrationNotFoundFault"));
    }

    @Test
    void aDeletedIntegrationNoLongerAppears() {
        String arn = arnOf(createIntegration("zetl-deleted"));

        query("Action", "DeleteIntegration", "IntegrationArn", arn)
                .then().statusCode(200)
                .body(containsString("zetl-deleted"));

        query("Action", "DescribeIntegrations")
                .then().statusCode(200)
                .body(not(containsString("zetl-deleted")));
    }

    @Test
    void deletingAnUnknownIntegrationIsNotFound() {
        query("Action", "DeleteIntegration", "IntegrationArn",
                "arn:aws:redshift:us-east-1:000000000000:integration:00000000-0000-0000-0000-000000000000")
                .then().statusCode(404)
                .body(containsString("IntegrationNotFoundFault"));
    }

    @Test
    void aDuplicateIntegrationNameIsRejected() {
        createIntegration("zetl-duplicate");

        query("Action", "CreateIntegration", "IntegrationName", "zetl-duplicate",
                "SourceArn", SOURCE, "TargetArn", TARGET)
                .then().statusCode(400)
                .body(containsString("IntegrationAlreadyExistsFault"));
    }

    @Test
    void anIntegrationNameOutsideTheModelledPatternIsRejected() {
        // CreateIntegration.IntegrationName is modelled as
        // ^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$: a letter first, then alphanumeric groups joined
        // by single hyphens. A leading digit or hyphen, an underscore, a doubled hyphen and a
        // trailing hyphen are each outside it, so AWS refuses names floci used to accept.
        for (String name : new String[] {"1zetl", "-zetl", "zetl_name", "zetl--name", "zetl-"}) {
            query("Action", "CreateIntegration", "IntegrationName", name,
                    "SourceArn", SOURCE, "TargetArn", TARGET)
                    .then().statusCode(400)
                    .body(containsString("InvalidParameterValue"))
                    .body(containsString("IntegrationName"));
        }
    }

    @Test
    void anIntegrationNameOverTheModelledLengthIsRejected() {
        query("Action", "CreateIntegration", "IntegrationName", "z".repeat(64),
                "SourceArn", SOURCE, "TargetArn", TARGET)
                .then().statusCode(400)
                .body(containsString("InvalidParameterValue"));
    }

    @Test
    void anIntegrationNameAtTheModelledLengthIsAccepted() {
        // 63 characters is the documented maximum, so the boundary itself must still create.
        String atLimit = "zetl" + "a".repeat(59);
        assertEquals(63, atLimit.length());
        createIntegration(atLimit);
    }

    @Test
    void createRequiresSourceAndTarget() {
        query("Action", "CreateIntegration", "IntegrationName", "zetl-missing")
                .then().statusCode(400)
                .body(containsString("SourceArn"));
    }

    @Test
    void tagsSurviveTheRoundTrip() {
        // The Query member is TagList, not Tags: an SDK serialises the list under its own name.
        query("Action", "CreateIntegration", "IntegrationName", "zetl-tagged",
                "SourceArn", SOURCE, "TargetArn", TARGET,
                "TagList.Tag.1.Key", "Environment", "TagList.Tag.1.Value", "dev")
                .then().statusCode(200);

        query("Action", "DescribeIntegrations")
                .then().statusCode(200)
                .body(containsString("<Key>Environment</Key>"))
                .body(containsString("<Value>dev</Value>"));
    }

    @Test
    void anAccountWithNoIntegrationsGetsAnEmptyList() {
        // Not an error: real Redshift answers an account with none with an empty Integrations list.
        query("Action", "DescribeIntegrations")
                .then().statusCode(200)
                .body(containsString("<DescribeIntegrationsResult>"));
    }

    @Test
    void descriptionAndEncryptionContextAreStoredAndReturned() {
        query("Action", "CreateIntegration", "IntegrationName", "zetl-full",
                "SourceArn", SOURCE, "TargetArn", TARGET,
                "Description", "nightly replica",
                "KMSKeyId", "arn:aws:kms:us-east-1:000000000000:key/abc",
                "AdditionalEncryptionContext.entry.1.key", "team",
                "AdditionalEncryptionContext.entry.1.value", "data")
                .then().statusCode(200);

        query("Action", "DescribeIntegrations")
                .then().statusCode(200)
                .body(containsString("<Description>nightly replica</Description>"))
                .body(containsString("<key>team</key>"))
                .body(containsString("<value>data</value>"));
    }

    @Test
    void encryptionContextWithoutAKmsKeyIsRejected() {
        query("Action", "CreateIntegration", "IntegrationName", "zetl-nokms",
                "SourceArn", SOURCE, "TargetArn", TARGET,
                "AdditionalEncryptionContext.entry.1.key", "team",
                "AdditionalEncryptionContext.entry.1.value", "data")
                .then().statusCode(400)
                .body(containsString("KMSKeyId"));
    }

    @Test
    void describeCrossesAPageBoundaryAndResumesFromTheMarker() {
        // 21 records against the smallest legal page size guarantees more than one page. Other
        // tests in this class share the store, so the assertions below are about the traversal
        // rather than absolute counts.
        for (int i = 0; i < 21; i++) {
            query("Action", "CreateIntegration", "IntegrationName", String.format("zetl-page-%02d", i),
                    "SourceArn", SOURCE + "-" + i, "TargetArn", TARGET)
                    .then().statusCode(200);
        }

        String firstPage = query("Action", "DescribeIntegrations", "MaxRecords", "20")
                .then().statusCode(200).extract().body().asString();
        assertEquals(20, arnsIn(firstPage).size(), "a full page must carry exactly MaxRecords records");
        assertTrue(firstPage.contains("<Marker>"), "a non-terminal page must carry a Marker");

        // Walk every page, proving the marker resumes correctly rather than repeating or skipping.
        List<String> seen = new ArrayList<>(arnsIn(firstPage));
        String marker = between(firstPage, "<Marker>", "</Marker>");
        String page = firstPage;
        int guard = 0;
        while (page.contains("<Marker>") && guard++ < 20) {
            marker = between(page, "<Marker>", "</Marker>");
            page = query("Action", "DescribeIntegrations", "MaxRecords", "20", "Marker", marker)
                    .then().statusCode(200).extract().body().asString();
            List<String> arns = arnsIn(page);
            assertFalse(arns.isEmpty(), "a page reached through a Marker must not be empty");
            for (String arn : arns) {
                assertFalse(seen.contains(arn), "no record may appear on two pages: " + arn);
            }
            seen.addAll(arns);
        }
        assertFalse(page.contains("<Marker>"), "the final page must omit Marker");

        // The traversal must have seen every record exactly once.
        List<String> everything = arnsIn(
                query("Action", "DescribeIntegrations", "MaxRecords", "100")
                        .then().statusCode(200).extract().body().asString());
        assertEquals(everything.size(), seen.size(), "paging must omit nothing");
        assertTrue(seen.containsAll(everything), "paging must return the same records as one page");
        assertTrue(seen.size() >= 21, "the 21 records created here must all be reachable");
    }

    private static List<String> arnsIn(String xml) {
        List<String> arns = new ArrayList<>();
        int from = 0;
        while ((from = xml.indexOf("<IntegrationArn>", from)) >= 0) {
            int start = from + "<IntegrationArn>".length();
            arns.add(xml.substring(start, xml.indexOf("</IntegrationArn>", start)));
            from = start;
        }
        return arns;
    }

    private static String between(String xml, String open, String close) {
        int start = xml.indexOf(open) + open.length();
        return xml.substring(start, xml.indexOf(close, start));
    }

    @Test
    void maxRecordsOutsideTheDocumentedRangeIsRejected() {
        query("Action", "DescribeIntegrations", "MaxRecords", "5")
                .then().statusCode(400)
                .body(containsString("MaxRecords"));
        query("Action", "DescribeIntegrations", "MaxRecords", "101")
                .then().statusCode(400)
                .body(containsString("MaxRecords"));
    }

    @Test
    void filteringBySourceArnNarrowsTheResult() {
        query("Action", "CreateIntegration", "IntegrationName", "zetl-filtered",
                "SourceArn", SOURCE + "-filtered", "TargetArn", TARGET)
                .then().statusCode(200);
        query("Action", "CreateIntegration", "IntegrationName", "zetl-unfiltered",
                "SourceArn", SOURCE + "-other", "TargetArn", TARGET)
                .then().statusCode(200);

        query("Action", "DescribeIntegrations",
                "Filters.DescribeIntegrationsFilter.1.Name", "source-arn",
                "Filters.DescribeIntegrationsFilter.1.Values.Value.1", SOURCE + "-filtered")
                .then().statusCode(200)
                .body(containsString("zetl-filtered"))
                .body(not(containsString("zetl-unfiltered")));
    }

    @Test
    void anUnrecognisedFilterNameIsRejected() {
        query("Action", "DescribeIntegrations",
                "Filters.DescribeIntegrationsFilter.1.Name", "not-a-filter",
                "Filters.DescribeIntegrationsFilter.1.Values.Value.1", "x")
                .then().statusCode(400);
    }

    @Test
    void anInvalidMarkerIsRejected() {
        query("Action", "DescribeIntegrations", "Marker", "nonsense")
                .then().statusCode(400)
                .body(containsString("Marker"));
    }
}
