package io.github.hectorvent.floci.services.cloudwatch.metricstreams;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;

/**
 * Metric streams reach the emulator over the Query protocol from the Terraform AWS provider
 * and over JSON with an X-Amz-Target header from the CLI and SDK v3. Both routes are covered.
 */
@QuarkusTest
class CloudWatchMetricStreamsIntegrationTest {

    private static final String CW_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";

    private static final String JSON_1_0 = "application/x-amz-json-1.0";

    private static final String FIREHOSE_ARN =
            "arn:aws:firehose:us-east-1:000000000000:deliverystream/metrics";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/metric-stream-role";

    @BeforeAll
    static void registerJsonParser() {
        RestAssured.registerParser(JSON_1_0, Parser.JSON);
    }

    private static RequestSpecification query(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CW_SCOPE)
                .formParam("Action", action);
    }

    // RestAssured has no built-in serializer for the x-amz-json content types; send raw text.
    private static RequestSpecification json(String target, String body) {
        return given()
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON_1_0, ContentType.TEXT)))
                .contentType(JSON_1_0)
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + target)
                .body(body);
    }

    private static String putStream(String name) {
        return query("PutMetricStream")
                .formParam("Name", name)
                .formParam("FirehoseArn", FIREHOSE_ARN)
                .formParam("RoleArn", ROLE_ARN)
                .formParam("OutputFormat", "json")
                .formParam("ExcludeFilters.member.1.Namespace", "AWS/S3")
                .formParam("ExcludeFilters.member.1.MetricNames.member.1", "BucketSizeBytes")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("PutMetricStreamResponse.PutMetricStreamResult.Arn",
                        equalTo("arn:aws:cloudwatch:us-east-1:000000000000:metric-stream/" + name))
                .extract().path("PutMetricStreamResponse.PutMetricStreamResult.Arn");
    }

    @Test
    void putThenGetReturnsTheDefinition() {
        putStream("query-round-trip");

        query("GetMetricStream")
                .formParam("Name", "query-round-trip")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetMetricStreamResponse.GetMetricStreamResult.Name", equalTo("query-round-trip"))
                .body("GetMetricStreamResponse.GetMetricStreamResult.FirehoseArn", equalTo(FIREHOSE_ARN))
                .body("GetMetricStreamResponse.GetMetricStreamResult.RoleArn", equalTo(ROLE_ARN))
                .body("GetMetricStreamResponse.GetMetricStreamResult.State", equalTo("running"))
                .body("GetMetricStreamResponse.GetMetricStreamResult.OutputFormat", equalTo("json"))
                .body("GetMetricStreamResponse.GetMetricStreamResult.CreationDate", containsString("T"))
                .body("GetMetricStreamResponse.GetMetricStreamResult.ExcludeFilters.member.Namespace",
                        equalTo("AWS/S3"))
                .body("GetMetricStreamResponse.GetMetricStreamResult.ExcludeFilters.member.MetricNames.member",
                        equalTo("BucketSizeBytes"));
    }

    @Test
    void getUnknownStreamReturnsResourceNotFoundException() {
        query("GetMetricStream")
                .formParam("Name", "no-such-stream")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundException"));
    }

    @Test
    void stoppingAnUnknownStreamIsInvalidParameterValue() {
        query("StopMetricStreams")
                .formParam("Names.member.1", "no-such-stream")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    void putWithoutARoleIsMissingParameter() {
        query("PutMetricStream")
                .formParam("Name", "incomplete")
                .formParam("FirehoseArn", FIREHOSE_ARN)
                .formParam("OutputFormat", "json")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    void listStopStartAndDeleteOverQuery() {
        putStream("lifecycle-one");
        putStream("lifecycle-two");

        query("ListMetricStreams")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ListMetricStreamsResponse.ListMetricStreamsResult.Entries.member.Name",
                        hasItem("lifecycle-one"))
                .body("ListMetricStreamsResponse.ListMetricStreamsResult.Entries.member.Name",
                        hasItem("lifecycle-two"));

        query("StopMetricStreams")
                .formParam("Names.member.1", "lifecycle-one")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        query("GetMetricStream")
                .formParam("Name", "lifecycle-one")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetMetricStreamResponse.GetMetricStreamResult.State", equalTo("stopped"));

        query("StartMetricStreams")
                .formParam("Names.member.1", "lifecycle-one")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        query("GetMetricStream")
                .formParam("Name", "lifecycle-one")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetMetricStreamResponse.GetMetricStreamResult.State", equalTo("running"));

        query("DeleteMetricStream")
                .formParam("Name", "lifecycle-one")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        query("GetMetricStream")
                .formParam("Name", "lifecycle-one")
            .when()
                .post("/")
            .then()
                .statusCode(404);
    }

    @Test
    void listPagesOverBothProtocols() {
        putStream("paged-a");
        putStream("paged-b");

        String token = query("ListMetricStreams")
                .formParam("MaxResults", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ListMetricStreamsResponse.ListMetricStreamsResult.NextToken", notNullValue())
                .extract().path("ListMetricStreamsResponse.ListMetricStreamsResult.NextToken");

        json("ListMetricStreams", "{\"MaxResults\": 1, \"NextToken\": \"" + token + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Entries", hasSize(1));

        query("ListMetricStreams")
                .formParam("NextToken", "not base64!")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidNextToken"));
    }

    @Test
    void tagsSuppliedOnPutAreReadableThroughListTagsForResource() {
        String arn = query("PutMetricStream")
                .formParam("Name", "tagged")
                .formParam("FirehoseArn", FIREHOSE_ARN)
                .formParam("RoleArn", ROLE_ARN)
                .formParam("OutputFormat", "json")
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "platform")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("PutMetricStreamResponse.PutMetricStreamResult.Arn");

        query("ListTagsForResource")
                .formParam("ResourceARN", arn)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Key>team</Key>"))
                .body(containsString("<Value>platform</Value>"));

        query("UntagResource")
                .formParam("ResourceARN", arn)
                .formParam("TagKeys.member.1", "team")
            .when()
                .post("/")
            .then()
                .statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceARN", arn)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<Key>team</Key>")));
    }

    @Test
    void theSameOperationsAreServedOverTheJsonProtocol() {
        json("PutMetricStream", "{\"Name\": \"json-round-trip\", \"FirehoseArn\": \"" + FIREHOSE_ARN
                + "\", \"RoleArn\": \"" + ROLE_ARN + "\", \"OutputFormat\": \"opentelemetry1.0\","
                + " \"IncludeFilters\": [{\"Namespace\": \"AWS/Lambda\", \"MetricNames\": [\"Errors\"]}]}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Arn", equalTo("arn:aws:cloudwatch:us-east-1:000000000000:metric-stream/json-round-trip"));

        json("GetMetricStream", "{\"Name\": \"json-round-trip\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Name", equalTo("json-round-trip"))
                .body("State", equalTo("running"))
                .body("OutputFormat", equalTo("opentelemetry1.0"))
                .body("IncludeFilters[0].Namespace", equalTo("AWS/Lambda"))
                .body("IncludeFilters[0].MetricNames[0]", equalTo("Errors"));

        json("ListMetricStreams", "{}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Entries.Name", hasItem("json-round-trip"));

        json("GetMetricStream", "{\"Name\": \"json-missing\"}")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }
}
