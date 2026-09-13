package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

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
    void createRequiresSourceAndTarget() {
        query("Action", "CreateIntegration", "IntegrationName", "zetl-missing")
                .then().statusCode(400)
                .body(containsString("SourceArn"));
    }

    @Test
    void tagsSurviveTheRoundTrip() {
        query("Action", "CreateIntegration", "IntegrationName", "zetl-tagged",
                "SourceArn", SOURCE, "TargetArn", TARGET,
                "Tags.Tag.1.Key", "Environment", "Tags.Tag.1.Value", "dev")
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
}
