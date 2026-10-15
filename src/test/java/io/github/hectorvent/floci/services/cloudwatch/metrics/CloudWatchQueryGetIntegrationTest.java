package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CloudWatchQueryGetIntegrationTest {

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=test/20261013/us-east-1/" + service + "/aws4_request";
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "AUTHORIZATION", "aUtHoRiZaTiOn"})
    void getReadsMetricWrittenThroughPublicPost(String headerName) {
        Instant now = Instant.now();
        String namespace = "Example/QueryGet/" + headerName;
        given().header(headerName, authorization("monitoring"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutMetricData").formParam("Version", "2010-08-01")
                .formParam("Namespace", namespace)
                .formParam("MetricData.member.1.MetricName", "Requests")
                .formParam("MetricData.member.1.Value", "17")
                .formParam("MetricData.member.1.Timestamp", now.toString())
                .post("/").then().statusCode(200);
        given().header(headerName, authorization("monitoring"))
                .queryParam("Action", "GetMetricStatistics").queryParam("Version", "2010-08-01")
                .queryParam("Namespace", namespace).queryParam("MetricName", "Requests")
                .queryParam("StartTime", now.minusSeconds(120).toString())
                .queryParam("EndTime", now.plusSeconds(120).toString()).queryParam("Period", "60")
                .queryParam("Statistics.member.1", "Sum")
                .get("/").then().statusCode(200)
                .body("GetMetricStatisticsResponse.GetMetricStatisticsResult.Datapoints.member.Sum", equalTo("17.0"));
    }

    @Test
    void operationFallbackUsesTheSamePublicHandler() {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Operation", "ListMetrics")
                .get("/").then().statusCode(200).body(containsString("ListMetricsResponse"));
    }

    @Test
    void s3ScopeAndUnrelatedQueryKeepBucketListing() {
        given().header("Authorization", authorization("s3"))
                .queryParam("Action", "GetMetricStatistics")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
        given().queryParam("X-Amz-Credential", "test/20261013/us-east-1/s3/aws4_request")
                .queryParam("Action", "GetMetricStatistics")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
        given().queryParam("Action", "UnknownAction")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
        given().queryParam("Action", "TagResource")
                .get("/").then().statusCode(200).body(containsString("ListAllMyBucketsResult"));
    }

    @Test
    void cloudWatchValidationStillReturnsQueryErrors() {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Action", "PutDashboard").queryParam("Version", "2010-08-01")
                .get("/").then().statusCode(400).body(containsString("ErrorResponse"));
    }

    @ParameterizedTest
    @CsvSource({"false, , ", "false, text/html, ", "false, , AmazonSSM.GetParameter",
            "true, , ", "true, text/html, NotAService.Operation"})
    void signingScopeSelectsCloudWatchRegardlessOfBrowserOrTargetHeaders(
            boolean presigned, String accept, String target) {
        RequestSpecification request = given().queryParam("Action", "ListMetrics");
        if (presigned) {
            request.queryParam("X-Amz-Credential", "test/20261013/us-east-1/monitoring/aws4_request");
        } else {
            request.header("Authorization", authorization("monitoring"));
        }
        if (accept != null) {
            request.header("Accept", accept);
        }
        if (target != null) {
            request.header("X-Amz-Target", target);
        }
        request.get("/").then().statusCode(200).body(containsString("ListMetricsResponse"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "ListMetrics, OMITTED, 200", "ListMetrics, 2009-05-15, 200", "ListMetrics, 2010-08-01, 200",
            "ListMetrics, '', 404", "ListMetrics, 2000-01-01, 404",
            "ListMetricStreams, OMITTED, 404", "ListMetricStreams, 2009-05-15, 404",
            "ListMetricStreams, 2010-08-01, 200"
    }, nullValues = "OMITTED")
    void versionSelectsTheCurrentOrLegacyOperations(String action, String version, int status) {
        RequestSpecification request = given().header("Authorization", authorization("monitoring"))
                .queryParam("Action", action);
        if (version != null) {
            request.queryParam("Version", version);
        }
        if (status == 200) {
            request.get("/").then().statusCode(status).body(containsString(action + "Response"));
        } else {
            request.get("/").then().statusCode(status)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidAction"));
        }
    }

    @ParameterizedTest
    @CsvSource({"ListMetrics,UnknownAction,200", "UnknownAction,ListMetrics,404", "ListMetrics,ListMetrics,200"})
    void theFirstRepeatedActionWins(String first, String second, int status) {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Action", first, second)
                .get("/").then().statusCode(status)
                .body(containsString(status == 200 ? "ListMetricsResponse" : "InvalidAction"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UnknownAction", ""})
    void signedUnknownCloudWatchActionsReturnAwsErrors(String action) {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Action", action).queryParam("Version", "2010-08-01")
                .get("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("InvalidAction"));
    }

    @Test
    void signedCloudWatchGetWithoutAnActionReturnsUnknownOperation() {
        given().header("Authorization", authorization("monitoring"))
                .queryParam("Version", "2010-08-01")
                .get("/").then().statusCode(404).body(containsString("<UnknownOperationException/>"));
    }
}
