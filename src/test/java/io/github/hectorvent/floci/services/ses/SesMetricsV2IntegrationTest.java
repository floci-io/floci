package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Integration tests for {@code POST /v2/email/metrics/batch} (BatchGetMetricData).
 *
 * <p>The wire behaviour asserted here is probe-confirmed against real SES (2026-09-23, us-east-1):
 * the VDM gate, daily buckets, a sparse series, the 60 day interval cap, and the order in which the
 * request is validated. The exclusive end of the range is pinned in {@code SesMetricDataTest},
 * which can put an event on the boundary instant. Uses an isolated signing region so no other
 * test's sends reach these counts.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesMetricsV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/sa-east-1/ses/aws4_request";
    private static final String SENDER = "metrics-sender@example.com";

    private static final long DAY = Duration.ofDays(1).toSeconds();

    // One reading of the clock for the whole class: two calls a second apart would turn the
    // equal-dates case below into a valid one-second interval.
    private static final long NOW = Instant.now().getEpochSecond();

    private static long start() {
        return NOW - Duration.ofDays(1).toSeconds();
    }

    private static long end() {
        return NOW + Duration.ofDays(1).toSeconds();
    }

    private static String query(String id, String metric) {
        return """
            {"Id": "%s", "Namespace": "VDM", "Metric": "%s", "StartDate": %d, "EndDate": %d}
            """.formatted(id, metric, start(), end());
    }

    private static Response post(String queriesJson) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Queries\": [" + queriesJson + "]}")
        .when().post("/v2/email/metrics/batch");
    }

    private static void badValue(String dimension, String value, String message) {
        dimensionQuery(dimension, value).then().statusCode(400)
                .body("message", equalTo(message));
    }

    private static void goodValue(String dimension, String value) {
        dimensionQuery(dimension, value).then().statusCode(200);
    }

    private static Response dimensionQuery(String dimension, String value) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "v", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"%s": "%s"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(dimension, value, start(), end()))
        .when().post("/v2/email/metrics/batch");
    }

    private static int total(Response response, String id) {
        List<Integer> values = response.path("Results.find { it.Id == '" + id + "' }.Values");
        return values.stream().mapToInt(Integer::intValue).sum();
    }

    private static void send(String destination) {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["%s"]},
                      "Content": {"Simple": {"Subject": {"Data": "metrics"},
                                             "Body": {"Text": {"Data": "hi"}}}}
                    }
                    """.formatted(SENDER, destination))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);
    }

    @Test
    @Order(0)
    void batchGetMetricData_vdmDisabled_reportsTheGateBeforeItValidatesTheRequest() {
        // The body is invalid in three ways; with VDM off none of that is reached.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Queries\": []}")
        .when().post("/v2/email/metrics/batch").then().statusCode(404)
                .body("message", containsString(
                        "To use this feature you must enable Virtual Deliverability Manager"));
    }

    @Test
    @Order(1)
    void enableVdmAndVerifyTheSender() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    @Test
    @Order(2)
    void sendOneOfEachOutcome() {
        send("ordinary@example.com");
        send("bounce@simulator.amazonses.com");
        send("complaint@simulator.amazonses.com");
    }

    @Test
    @Order(3)
    void batchGetMetricData_countsEachOutcomeOnce() {
        Response response = post(String.join(",",
                query("send", "SEND"),
                query("delivery", "DELIVERY"),
                query("bounce", "PERMANENT_BOUNCE"),
                query("complaint", "COMPLAINT"),
                query("delivered", "DELIVERY_COMPLAINT")));
        response.then().statusCode(200);

        // Summed across buckets, since the three sends can straddle UTC midnight. Results come
        // back keyed by Id, in an order AWS does not promise.
        assertEquals(3, total(response, "send"));
        assertEquals(2, total(response, "delivery"));
        assertEquals(1, total(response, "bounce"));
        assertEquals(1, total(response, "complaint"));
        assertEquals(1, total(response, "delivered"));
    }

    @Test
    @Order(4)
    void batchGetMetricData_aMetricWithNoDataKeepsItsMembers() {
        // Probed: an empty series is a Results entry with empty arrays, never an omission or a zero,
        // and Errors is present on every response even though nothing ever populates it.
        post(String.join(",", query("open", "OPEN"), query("transient", "TRANSIENT_BOUNCE")))
        .then().statusCode(200)
                .body("Results", hasSize(2))
                .body("Results.find { it.Id == 'open' }.Timestamps", empty())
                .body("Results.find { it.Id == 'open' }.Values", empty())
                .body("Results.find { it.Id == 'transient' }.Values", empty())
                .body("Errors", empty());
    }

    @Test
    @Order(5)
    void batchGetMetricData_bucketsByDay() {
        Response response = post(query("buckets", "SEND"));
        response.then().statusCode(200);
        List<Float> timestamps = response.path("Results[0].Timestamps");
        List<Integer> values = response.path("Results[0].Values");

        // The boundary is read from the response rather than the wall clock, so the assertions
        // hold even when the suite crosses UTC midnight between the sends and this test.
        for (Float timestamp : timestamps) {
            assertEquals(0L, timestamp.longValue() % DAY, "bucket is not a whole UTC day");
        }
        assertEquals(3, values.stream().mapToInt(Integer::intValue).sum());

        // A window ending at the first bucket stops before it.
        long firstBucket = timestamps.get(0).longValue();
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "before", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(firstBucket - DAY, firstBucket))
        .when().post("/v2/email/metrics/batch").then().statusCode(200)
                .body("Results[0].Values", empty());
    }

    @Test
    @Order(6)
    void batchGetMetricData_filtersOnTheIdentityDimension() {
        Response response = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "mine", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"EMAIL_IDENTITY": "example.com"},
                                  "StartDate": %d, "EndDate": %d},
                                 {"Id": "theirs", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"EMAIL_IDENTITY": "elsewhere.example"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end(), start(), end()))
        .when().post("/v2/email/metrics/batch");
        response.then().statusCode(200)
                .body("Results.find { it.Id == 'theirs' }.Values", empty());
        // Summed, since the sends can straddle UTC midnight and land in two buckets.
        assertEquals(3, total(response, "mine"));
    }

    @Test
    @Order(7)
    void batchGetMetricData_filtersOnTheConfigurationSetEverySendPathResolves() {
        // One send down each of the three paths, all naming the same set. A regression in any one
        // of the three assignments shows up here as a count below three.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ConfigurationSetName\": \"metrics-set\"}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s", "ConfigurationSetName": "metrics-set",
                     "Destination": {"ToAddresses": ["simple@example.com"]},
                     "Content": {"Simple": {"Subject": {"Data": "s"},
                                            "Body": {"Text": {"Data": "hi"}}}}}
                    """.formatted(SENDER))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        String raw = "From: " + SENDER + "\r\nTo: raw@example.com\r\nSubject: r\r\n\r\nhi\r\n";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s", "ConfigurationSetName": "metrics-set",
                     "Destination": {"ToAddresses": ["raw@example.com"]},
                     "Content": {"Raw": {"Data": "%s"}}}
                    """.formatted(SENDER, Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8))))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"TemplateName": "metrics-cvet", "FromEmailAddress": "%s",
                     "TemplateSubject": "verify", "TemplateContent": "<p>verify</p>",
                     "SuccessRedirectionURL": "https://example.com/ok",
                     "FailureRedirectionURL": "https://example.com/no"}
                    """.formatted(SENDER))
        .when().post("/v2/email/custom-verification-email-templates").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"EmailAddress": "cvet@example.com", "TemplateName": "metrics-cvet",
                     "ConfigurationSetName": "metrics-set"}
                    """)
        .when().post("/v2/email/outbound-custom-verification-emails").then().statusCode(200);

        Response response = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "set", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"CONFIGURATION_SET": "metrics-set"},
                                  "StartDate": %d, "EndDate": %d},
                                 {"Id": "other", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"CONFIGURATION_SET": "no-such-set"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end(), start(), end()))
        .when().post("/v2/email/metrics/batch");
        response.then().statusCode(200).body("Results.find { it.Id == 'other' }.Values", empty());

        assertEquals(3, total(response, "set"));

        // A send naming a blank set resolves to no set, so it must not join any set's count. A
        // blank dimension value cannot be used to check that: AWS refuses one outright.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s", "ConfigurationSetName": "",
                     "Destination": {"ToAddresses": ["blank@example.com"]},
                     "Content": {"Simple": {"Subject": {"Data": "b"},
                                            "Body": {"Text": {"Data": "hi"}}}}}
                    """.formatted(SENDER))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        Response after = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "set", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"CONFIGURATION_SET": "metrics-set"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end()))
        .when().post("/v2/email/metrics/batch");
        after.then().statusCode(200);
        assertEquals(3, total(after, "set"));
    }

    @Test
    @Order(8)
    void batchGetMetricData_filtersOnTheTenantTheSendNamed() {
        // Without a send that actually names a tenant, dropping the setTenantName assignments
        // would leave every positive tenant metric at zero and the suite green.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\": \"metrics-tenant\"}")
        .when().post("/v2/email/tenants").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"TenantName": "metrics-tenant",
                     "ResourceArn": "arn:aws:ses:sa-east-1:000000000000:identity/%s"}
                    """.formatted(SENDER))
        .when().post("/v2/email/tenants/resources").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s", "TenantName": "metrics-tenant",
                     "Destination": {"ToAddresses": ["tenant@example.com"]},
                     "Content": {"Simple": {"Subject": {"Data": "t"},
                                            "Body": {"Text": {"Data": "hi"}}}}}
                    """.formatted(SENDER))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        // The raw path stores the tenant too, and only a raw send covers its own assignment.
        String raw = "From: " + SENDER + "\r\nTo: tenant-raw@example.com\r\nSubject: t\r\n\r\nhi\r\n";
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s", "TenantName": "metrics-tenant",
                     "Destination": {"ToAddresses": ["tenant-raw@example.com"]},
                     "Content": {"Raw": {"Data": "%s"}}}
                    """.formatted(SENDER, Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8))))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        Response response = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "mine", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"TENANT_NAME": "metrics-tenant"},
                                  "StartDate": %d, "EndDate": %d},
                                 {"Id": "theirs", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"TENANT_NAME": "another-tenant"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end(), start(), end()))
        .when().post("/v2/email/metrics/batch");
        response.then().statusCode(200)
                .body("Results.find { it.Id == 'theirs' }.Values", empty());
        assertEquals(2, total(response, "mine"));
    }

    @Test
    @Order(9)
    void batchGetMetricData_validatesInTheProbedOrder() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Queries\": []}")
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'queries' failed "
                        + "to satisfy constraint: Member must have length greater than or equal to 1"));

        StringBuilder eleven = new StringBuilder(query("q0", "SEND"));
        for (int i = 1; i < 11; i++) {
            eleven.append(",").append(query("q" + i, "SEND"));
        }
        post(eleven.toString()).then().statusCode(400)
                .body("message", containsString("Member must have length less than or equal to 10"));

        // A duplicate id outranks both the reversed dates and the interval cap.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "same", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d},
                                 {"Id": "same", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(end(), start(), end(), start()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("All queries must have a unique identifier."));

        // TENANT_NAME is a valid dimension on AWS even though the pinned SDK model omits it, so it
        // must not be refused as unsupported.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "tenant", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"TENANT_NAME": "no-such-tenant"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end()))
        .when().post("/v2/email/metrics/batch").then().statusCode(200)
                .body("Results[0].Values", empty());

        // An unknown dimension name outranks the member constraints.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "d", "Namespace": "VDM", "Metric": "NOT_A_METRIC",
                                  "Dimensions": {"NOT_A_DIMENSION": "x"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("Unsupported dimension provided."));

        // Member constraints aggregate into one message, metric first, then namespace, then id.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "", "Namespace": "NOT_VDM", "Metric": "NOT_A_METRIC",
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(end(), start()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", containsString("3 validation errors detected:"))
                .body("message", containsString("'queries.1.member.metric'"))
                .body("message", containsString("'queries.1.member.namespace'"))
                .body("message", containsString("'queries.1.member.id'"));

        // Each dimension validates its own value, with its own message; all probe-confirmed.
        badValue("EMAIL_IDENTITY", "", "Invalid email identity provided.");
        badValue("EMAIL_IDENTITY", "*", "Invalid email identity provided.");
        badValue("EMAIL_IDENTITY", "user@", "Invalid email identity provided.");
        badValue("ISP", "", "Invalid ISP name provided.");
        badValue("ISP", "Gmail.com", "Invalid ISP name provided.");
        badValue("CONFIGURATION_SET", "", "The configuration set name must be specified.");
        badValue("CONFIGURATION_SET", "has space", "Invalid configuration set name <has space>: "
                + "only alphanumeric ASCII characters, '_', and '-' are allowed.");
        badValue("TENANT_NAME", "", "TenantName cannot be empty");
        badValue("TENANT_NAME", "*", "Invalid tenant name <*>: "
                + "only alphanumeric ASCII characters, '_', and '-' are allowed.");
        // The shapes the probe accepted still pass.
        goodValue("EMAIL_IDENTITY", "UPPER@Example.COM");
        goodValue("ISP", "Not-An_Isp");

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "same-instant", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), start()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("Expected start date to be before the end date."));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "wide", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(end() - 61 * DAY, end()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("Invalid interval, can request at most 60 days. "
                        + "Please partition your requested interval."));

        // Four keys became reachable when TENANT_NAME was added; the model caps the map at three.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "four", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"ISP": "UNKNOWN_ISP", "EMAIL_IDENTITY": "example.com",
                                                 "CONFIGURATION_SET": "s", "TENANT_NAME": "t"},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at "
                        + "'queries.1.member.dimensions' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 3"));

        // Probed request-wide: a bad dimension value in any query outranks a bad date in any other.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "dates", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d},
                                 {"Id": "value", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": {"EMAIL_IDENTITY": ""},
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(end(), start(), start(), end()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("Invalid email identity provided."));

        // A timestamp outside Instant's range is the caller's problem, not a server error.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "huge", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": 1, "EndDate": 999999999999999999}]}
                    """)
        .when().post("/v2/email/metrics/batch").then().statusCode(400);

        // A window that parses but sits within a day of Instant's upper bound must still answer,
        // rather than overflowing while the aggregation window is widened.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "edge", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": "+1000000000-12-01T00:00:00Z",
                                  "EndDate": "+1000000000-12-31T00:00:00Z"}]}
                    """)
        .when().post("/v2/email/metrics/batch").then().statusCode(200)
                .body("Results[0].Values", empty());

        // A list member that is not an array is a deserialization failure too, not an empty list.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"Queries\": {}}")
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("Start of structure or map found where not expected."));

        // A map member that is not an object is a deserialization failure, not an absent filter.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "shape", "Namespace": "VDM", "Metric": "SEND",
                                  "Dimensions": [],
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(start(), end()))
        .when().post("/v2/email/metrics/batch").then().statusCode(400)
                .body("message", equalTo("Start of list found where not expected"));

        // Sixty days exactly is accepted. Both ends come from one reading of the clock, so a
        // second boundary between two calls cannot turn this into sixty days and one second.
        long capEnd = end();
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"Queries": [{"Id": "cap", "Namespace": "VDM", "Metric": "SEND",
                                  "StartDate": %d, "EndDate": %d}]}
                    """.formatted(capEnd - 60 * DAY, capEnd))
        .when().post("/v2/email/metrics/batch").then().statusCode(200);
    }

    @Test
    @Order(10)
    void cleanUpVdmAndIdentity() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"DISABLED\"}}")
        .when().put("/v2/email/account/vdm");
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/identities/" + SENDER);
    }
}
