package io.floci.aws.services.cloudwatch.metrics;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * PutMetricData datums nested under EntityMetricData, as the CloudWatch Agent sends them,
 * must be stored under the request namespace on every protocol path. floci-io/floci#6066.
 */
@QuarkusTest
class CloudWatchMetricsEntityMetricDataIntegrationTest {

    private static final String CW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/monitoring/aws4_request";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";

    @BeforeAll
    static void registerJsonParser() {
        RestAssured.registerParser(JSON_1_0, Parser.JSON);
    }

    private static RequestSpecification json(String operation) {
        return given()
                .header("Authorization", CW_AUTH)
                .contentType(JSON_1_0)
                .config(config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON_1_0, ContentType.TEXT)))
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation);
    }

    private static RequestSpecification query(String action) {
        return given()
                .header("Authorization", CW_AUTH)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-08-01");
    }

    @Test
    void jsonEntityMetricDataOnlyIsListedAndAggregated() {
        long now = Instant.now().getEpochSecond();
        json("PutMetricData")
                .body("""
                        {"Namespace":"CWAgent/Json","MetricData":[],
                         "EntityMetricData":[{
                           "Entity":{"KeyAttributes":{"Type":"AWS::Resource",
                             "ResourceType":"AWS::EC2::Instance","Identifier":"i-0123456789abcdef0"},
                             "Attributes":{}},
                           "MetricData":[
                             {"MetricName":"mem_used_percent","Value":6.5,"Unit":"Percent",
                              "StorageResolution":60,"Timestamp":%d,
                              "Dimensions":[{"Name":"host","Value":"h1"}]},
                             {"MetricName":"disk_used_percent","Value":0.75,"Unit":"Percent",
                              "StorageResolution":60,"Timestamp":%d,
                              "Dimensions":[{"Name":"host","Value":"h1"}]}]}],
                         "StrictEntityValidation":false}
                        """.formatted(now, now))
        .when().post("/")
        .then().statusCode(200);

        json("ListMetrics")
                .body("{\"Namespace\":\"CWAgent/Json\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Metrics.MetricName", containsInAnyOrder("mem_used_percent", "disk_used_percent"));

        json("GetMetricStatistics")
                .body("""
                        {"Namespace":"CWAgent/Json","MetricName":"mem_used_percent",
                         "Dimensions":[{"Name":"host","Value":"h1"}],
                         "StartTime":%d,"EndTime":%d,"Period":60,"Statistics":["Sum"]}
                        """.formatted(now - 120, now + 120))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Datapoints.Sum", hasItem(6.5f));
    }

    @Test
    void jsonTopLevelAndEntityMetricDataAreBothStored() {
        json("PutMetricData")
                .body("""
                        {"Namespace":"CWAgent/JsonMixed",
                         "MetricData":[{"MetricName":"TopLevel","Value":1}],
                         "EntityMetricData":[{"Entity":{"KeyAttributes":{"Type":"Service",
                           "Name":"checkout","Environment":"prod"}},
                           "MetricData":[{"MetricName":"FromEntity","Value":2}]}]}
                        """)
        .when().post("/")
        .then().statusCode(200);

        json("ListMetrics")
                .body("{\"Namespace\":\"CWAgent/JsonMixed\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Metrics.MetricName", containsInAnyOrder("TopLevel", "FromEntity"));
    }

    @Test
    void queryTopLevelAndEntityMetricDataAreListedAndAggregated() {
        Instant now = Instant.now();
        query("PutMetricData")
                .formParam("Namespace", "CWAgent/Query")
                .formParam("MetricData.member.1.MetricName", "TopLevel")
                .formParam("MetricData.member.1.Value", "1")
                .formParam("EntityMetricData.member.1.Entity.KeyAttributes.entry.1.key", "Type")
                .formParam("EntityMetricData.member.1.Entity.KeyAttributes.entry.1.value", "AWS::Resource")
                .formParam("EntityMetricData.member.1.MetricData.member.1.MetricName", "mem_used_percent")
                .formParam("EntityMetricData.member.1.MetricData.member.1.Value", "6.5")
                .formParam("EntityMetricData.member.1.MetricData.member.1.Timestamp", now.toString())
                .formParam("EntityMetricData.member.1.MetricData.member.1.Dimensions.member.1.Name", "host")
                .formParam("EntityMetricData.member.1.MetricData.member.1.Dimensions.member.1.Value", "h1")
                .formParam("StrictEntityValidation", "false")
        .when().post("/")
        .then().statusCode(200);

        query("ListMetrics")
                .formParam("Namespace", "CWAgent/Query")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ListMetricsResponse.ListMetricsResult.Metrics.member.MetricName",
                        containsInAnyOrder("TopLevel", "mem_used_percent"));

        query("GetMetricStatistics")
                .formParam("Namespace", "CWAgent/Query")
                .formParam("MetricName", "mem_used_percent")
                .formParam("Dimensions.member.1.Name", "host")
                .formParam("Dimensions.member.1.Value", "h1")
                .formParam("StartTime", now.minusSeconds(120).toString())
                .formParam("EndTime", now.plusSeconds(120).toString())
                .formParam("Period", "60")
                .formParam("Statistics.member.1", "Sum")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("GetMetricStatisticsResponse.GetMetricStatisticsResult.Datapoints.member.Sum",
                        equalTo("6.5"));
    }

    @Test
    void jsonNestedDatumWithoutMetricNameIsRejectedAndNothingIsStored() {
        json("PutMetricData")
                .body("""
                        {"Namespace":"CWAgent/JsonInvalid",
                         "MetricData":[{"MetricName":"TopLevel","Value":1}],
                         "EntityMetricData":[{"MetricData":[{"Value":2}]}]}
                        """)
        .when().post("/")
        .then()
                .statusCode(400)
                .header("x-amzn-query-error", equalTo("MissingParameter;Sender"))
                .body("__type", equalTo("MissingRequiredParameterException"))
                .body("message", equalTo(
                        "The parameter EntityMetricData.member.1.MetricData.member.1.MetricName is required."));

        json("ListMetrics")
                .body("{\"Namespace\":\"CWAgent/JsonInvalid\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Metrics", empty());
    }

    @Test
    void queryMemberWithoutMetricNameIsRejectedAndNothingIsStored() {
        query("PutMetricData")
                .formParam("Namespace", "CWAgent/QueryInvalid")
                .formParam("MetricData.member.1.Value", "1")
                .formParam("MetricData.member.2.MetricName", "Valid")
                .formParam("MetricData.member.2.Value", "2")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"))
                .body("ErrorResponse.Error.Message",
                        equalTo("The parameter MetricData.member.1.MetricName is required."));

        query("ListMetrics")
                .formParam("Namespace", "CWAgent/QueryInvalid")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ListMetricsResponse.ListMetricsResult.Metrics.member.size()", equalTo(0));
    }
}
