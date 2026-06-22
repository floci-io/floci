package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the SES V1 Query-protocol ConfigurationSet option APIs that
 * sit alongside event destinations: tracking options and reputation-metrics toggling.
 *
 * <p>Tracking options require a verified domain identity, matching real AWS (verified
 * 2026-06-21): an unverified CustomRedirectDomain yields {@code InvalidTrackingOptions},
 * and that check runs before configuration-set existence. Reputation metrics default to
 * disabled for a V1-created set, unlike the V2 create path which leaves them enabled.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetTrackingOptionsV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String DOMAIN = "track-v1.floci.test";
    private static final String CS = "v1-cs-tracking";

    private static RequestSpecification query(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", action);
    }

    @Test
    @Order(1)
    void setup_verifyDomain_andCreateConfigSet() {
        query("VerifyDomainIdentity").formParam("Domain", DOMAIN)
            .when().post("/").then().statusCode(200);
        query("CreateConfigurationSet").formParam("ConfigurationSet.Name", CS)
            .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void v1CreatedSet_reputationMetricsDefaultsDisabled() {
        query("DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "reputationOptions")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<ReputationMetricsEnabled>false</ReputationMetricsEnabled>"));
    }

    @Test
    @Order(3)
    void createTrackingOptions_unverifiedDomain_returns400() {
        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
            .formParam("TrackingOptions.CustomRedirectDomain", "never-verified-v1.example.com")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>InvalidTrackingOptions</Code>"))
            .body(containsString("is not verified under this account"));
    }

    @Test
    @Order(4)
    void createTrackingOptions_verifiedDomain_stored() {
        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
            .formParam("TrackingOptions.CustomRedirectDomain", DOMAIN)
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("CreateConfigurationSetTrackingOptionsResponse"));

        query("DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "trackingOptions")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<CustomRedirectDomain>" + DOMAIN + "</CustomRedirectDomain>"));
    }

    @Test
    @Order(5)
    void createTrackingOptions_alreadyExists_returns400() {
        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
            .formParam("TrackingOptions.CustomRedirectDomain", DOMAIN)
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>TrackingOptionsAlreadyExistsException</Code>"));
    }

    @Test
    @Order(6)
    void updateTrackingOptions_changesDomain() {
        query("VerifyDomainIdentity").formParam("Domain", "other." + DOMAIN)
            .when().post("/").then().statusCode(200);
        query("UpdateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
            .formParam("TrackingOptions.CustomRedirectDomain", "other." + DOMAIN)
        .when().post("/")
        .then().statusCode(200);

        query("DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "trackingOptions")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<CustomRedirectDomain>other." + DOMAIN + "</CustomRedirectDomain>"));
    }

    @Test
    @Order(7)
    void deleteTrackingOptions_removesThem() {
        query("DeleteConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("DeleteConfigurationSetTrackingOptionsResponse"));

        query("DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "trackingOptions")
        .when().post("/")
        .then().statusCode(200)
            .body(not(containsString("<TrackingOptions>")));
    }

    @Test
    @Order(8)
    void deleteTrackingOptions_whenNoneSet_returns400() {
        query("DeleteConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>TrackingOptionsDoesNotExistException</Code>"));
    }

    @Test
    @Order(9)
    void updateTrackingOptions_whenNoneSet_returns400() {
        query("UpdateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
            .formParam("TrackingOptions.CustomRedirectDomain", DOMAIN)
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>TrackingOptionsDoesNotExistException</Code>"));
    }

    @Test
    @Order(10)
    void trackingOptions_unknownConfigSet_domainCheckedFirst() {
        // Verified domain reaches the configuration-set lookup; unverified short-circuits first.
        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", "v1-cs-ghost")
            .formParam("TrackingOptions.CustomRedirectDomain", DOMAIN)
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ConfigurationSetDoesNotExist</Code>"));

        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", "v1-cs-ghost")
            .formParam("TrackingOptions.CustomRedirectDomain", "never-verified-v1.example.com")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>InvalidTrackingOptions</Code>"));
    }

    @Test
    @Order(11)
    void createTrackingOptions_missingDomain_returnsValidationError() {
        // No TrackingOptions.CustomRedirectDomain param at all: AWS treats the
        // tracking-options member as null and returns a framework ValidationError.
        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("Member must not be null"));
    }

    @Test
    @Order(12)
    void createTrackingOptions_blankDomain_returnsInvalidTrackingOptions() {
        // Present but empty CustomRedirectDomain: the structure is non-null but has
        // no usable field, so AWS reports InvalidTrackingOptions instead.
        query("CreateConfigurationSetTrackingOptions")
            .formParam("ConfigurationSetName", CS)
            .formParam("TrackingOptions.CustomRedirectDomain", "")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>InvalidTrackingOptions</Code>"))
            .body(containsString("At least one field of TrackingOptions must contain a value"));
    }

    @Test
    @Order(13)
    void updateReputationMetricsEnabled_togglesAndReflectsInDescribe() {
        query("UpdateConfigurationSetReputationMetricsEnabled")
            .formParam("ConfigurationSetName", CS)
            .formParam("Enabled", "true")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("UpdateConfigurationSetReputationMetricsEnabledResponse"));

        query("DescribeConfigurationSet")
            .formParam("ConfigurationSetName", CS)
            .formParam("ConfigurationSetAttributeNames.member.1", "reputationOptions")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<ReputationMetricsEnabled>true</ReputationMetricsEnabled>"));
    }

    @Test
    @Order(14)
    void reputationMetrics_unknownConfigSet_returns400() {
        query("UpdateConfigurationSetReputationMetricsEnabled")
            .formParam("ConfigurationSetName", "v1-cs-ghost")
            .formParam("Enabled", "true")
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("<Code>ConfigurationSetDoesNotExist</Code>"));
    }
}
