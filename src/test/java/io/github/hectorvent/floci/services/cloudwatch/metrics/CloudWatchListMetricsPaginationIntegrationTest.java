package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CloudWatchListMetricsPaginationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String CURRENT_TARGET = "GraniteServiceVersion20100801.";
    private static final String CONSOLE_TARGET = "CloudWatchVersion20130116.";

    @Test
    void listMetricsPaginatesQueryAndBothJsonTargets() throws Exception {
        String namespace = "Issue/Pagination/" + UUID.randomUUID();
        ObjectNode putRequest = MAPPER.createObjectNode();
        putRequest.put("Namespace", namespace);
        ArrayNode metrics = putRequest.putArray("MetricData");
        long now = Instant.now().getEpochSecond();
        for (int i = 0; i < 501; i++) {
            ObjectNode metric = metrics.addObject();
            metric.put("MetricName", "Requests");
            metric.put("Value", 1);
            metric.put("Timestamp", now);
            metric.putArray("Dimensions").addObject().put("Name", "Host").put("Value", "host-" + i);
        }
        metrics.addObject().put("MetricName", "Other").put("Value", 1);
        json(CURRENT_TARGET + "PutMetricData", putRequest, ACCOUNT, REGION).then().statusCode(200);

        Response queryFirst = query(namespace, "Requests", null, ACCOUNT, REGION).then().statusCode(200).extract()
                .response();
        String queryToken = queryFirst.xmlPath().getString("ListMetricsResponse.ListMetricsResult.NextToken");
        assertEquals(500, queryFirst.xmlPath().getList("ListMetricsResponse.ListMetricsResult.Metrics.member").size());
        assertNotNull(queryToken);
        Response querySecond = query(namespace, "Requests", queryToken, ACCOUNT, REGION)
                .then().statusCode(200).extract().response();
        assertEquals(1, querySecond.xmlPath().getList("ListMetricsResponse.ListMetricsResult.Metrics.member").size());
        assertFalse(querySecond.asString().contains("<NextToken>"));

        for (String target : new String[]{CURRENT_TARGET, CONSOLE_TARGET}) {
            ObjectNode listRequest = MAPPER.createObjectNode().put("Namespace", namespace)
                    .put("MetricName", "Requests");
            JsonNode first = MAPPER.readTree(json(target + "ListMetrics", listRequest, ACCOUNT, REGION)
                    .then().statusCode(200).extract().asString());
            assertEquals(500, first.path("Metrics").size());
            String token = first.path("NextToken").asText(null);
            assertNotNull(token);
            listRequest.put("NextToken", token);
            JsonNode second = MAPPER.readTree(json(target + "ListMetrics", listRequest, ACCOUNT, REGION)
                    .then().statusCode(200).extract().asString());
            assertEquals(1, second.path("Metrics").size());
            assertFalse(second.has("NextToken"));
            Set<String> hosts = new HashSet<>();
            for (JsonNode page : new JsonNode[]{first, second}) {
                for (JsonNode metric : page.path("Metrics")) {
                    assertEquals("Requests", metric.path("MetricName").asText());
                    hosts.add(metric.path("Dimensions").get(0).path("Value").asText());
                }
            }
            assertEquals(501, hosts.size());
            listRequest.put("NextToken", 123);
            json(target + "ListMetrics", listRequest, ACCOUNT, REGION).then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterValueException"));
        }

        for (Response invalid : new Response[]{
                query(namespace, "Requests", queryToken, "222222222222", REGION),
                query(namespace, "Requests", queryToken, ACCOUNT, "us-west-2"),
                query(namespace, "Other", queryToken, ACCOUNT, REGION),
                query(namespace, "Requests", "not-a-token", ACCOUNT, REGION)}) {
            invalid.then().statusCode(400).body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        }
    }

    private static Response query(String namespace, String metricName, String token, String account, String region) {
        RequestSpecification request = given().header("Authorization", authorization(account, region))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListMetrics")
                .formParam("Version", "2010-08-01")
                .formParam("Namespace", namespace)
                .formParam("MetricName", metricName);
        if (token != null) {
            request.formParam("NextToken", token);
        }
        return request.post("/");
    }

    private static Response json(String target, ObjectNode body, String account, String region) {
        return given()
                .header("Authorization", authorization(account, region))
                .contentType("application/x-amz-json-1.0")
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/x-amz-json-1.0", ContentType.TEXT)))
                .header("X-Amz-Target", target)
                .body(body.toString())
                .post("/");
    }

    private static String authorization(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261101/" + region + "/monitoring/aws4_request";
    }
}
