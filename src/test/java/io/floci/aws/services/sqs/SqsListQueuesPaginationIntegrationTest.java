package io.floci.aws.services.sqs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SqsListQueuesPaginationIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String PREFIX = "list-paging-";
    private static final List<String> QUEUE_NAMES =
            List.of(PREFIX + "a", PREFIX + "b", PREFIX + "c");

    private final List<String> queueUrls = new ArrayList<>();
    private String otherQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createQueues() {
        otherQueueUrl = given().contentType(FORM_CONTENT_TYPE)
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", "other-list-paging")
                .post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        for (String name : QUEUE_NAMES) {
            queueUrls.add(given().contentType(FORM_CONTENT_TYPE)
                    .formParam("Action", "CreateQueue")
                    .formParam("QueueName", name)
                    .post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl"));
        }
    }

    @AfterEach
    void deleteQueues() {
        List<String> all = new ArrayList<>(queueUrls);
        all.add(otherQueueUrl);
        for (String url : all) {
            given().contentType(FORM_CONTENT_TYPE)
                    .formParam("Action", "DeleteQueue")
                    .formParam("QueueUrl", url)
                    .post("/");
        }
    }

    @Test
    void queryProtocolPagesThroughEveryQueueOnce() {
        List<String> collected = new ArrayList<>();
        String token = null;
        do {
            XmlPath xml = queryListQueues("1", token).then().statusCode(200).extract().xmlPath();
            List<String> page = xml.getList("ListQueuesResponse.ListQueuesResult.QueueUrl");
            assertEquals(1, page.size());
            collected.addAll(page);
            token = xml.getString("ListQueuesResponse.ListQueuesResult.NextToken");
            if (token != null && token.isEmpty()) {
                token = null;
            }
        } while (token != null);

        assertEquals(queueUrls, collected);
    }

    @Test
    void queryProtocolOmitsNextTokenWithoutMaxResults() {
        queryListQueues(null, null)
                .then()
                .statusCode(200)
                .body(not(containsString("<NextToken>")))
                .body(containsString(PREFIX + "c"));
    }

    @Test
    void queryProtocolRejectsOutOfRangeMaxResults() {
        queryListQueues("1001", null)
                .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        queryListQueues("0", null)
                .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void queryProtocolRejectsInvalidNextToken() {
        queryListQueues("1", "garbage-token!")
                .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void jsonProtocolPagesThroughEveryQueueOnce() {
        List<String> collected = new ArrayList<>();
        String token = null;
        do {
            String body = "{\"QueueNamePrefix\":\"" + PREFIX + "\",\"MaxResults\":1"
                    + (token != null ? ",\"NextToken\":\"" + token + "\"" : "") + "}";
            JsonPath json = jsonListQueues(body).then().statusCode(200).extract().jsonPath();
            List<String> page = json.getList("QueueUrls");
            assertEquals(1, page.size());
            collected.addAll(page);
            token = json.getString("NextToken");
        } while (token != null);

        assertEquals(queueUrls, collected);
    }

    @Test
    void jsonProtocolReturnsNextTokenOnlyWhileMoreQueuesRemain() {
        String first = jsonListQueues("{\"QueueNamePrefix\":\"" + PREFIX + "\",\"MaxResults\":2}")
                .then().statusCode(200).extract().jsonPath().getString("NextToken");
        assertNotNull(first);

        JsonPath last = jsonListQueues("{\"QueueNamePrefix\":\"" + PREFIX + "\",\"MaxResults\":2,"
                + "\"NextToken\":\"" + first + "\"}").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(queueUrls.get(2)), last.getList("QueueUrls"));
        assertNull(last.getString("NextToken"));

        jsonListQueues("{\"QueueNamePrefix\":\"" + PREFIX + "\"}")
                .then()
                .statusCode(200)
                .body("$", not(hasKey("NextToken")));
    }

    @Test
    void jsonProtocolRejectsOutOfRangeMaxResults() {
        jsonListQueues("{\"MaxResults\":1001}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValue"));
    }

    @Test
    void jsonProtocolRejectsInvalidNextToken() {
        jsonListQueues("{\"MaxResults\":1,\"NextToken\":\"garbage-token!\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValue"));
    }

    private static Response queryListQueues(String maxResults, String nextToken) {
        RequestSpecification spec = given()
                .contentType(FORM_CONTENT_TYPE)
                .formParam("Action", "ListQueues")
                .formParam("QueueNamePrefix", PREFIX);
        if (maxResults != null) {
            spec.formParam("MaxResults", maxResults);
        }
        if (nextToken != null) {
            spec.formParam("NextToken", nextToken);
        }
        return spec.post("/");
    }

    private static Response jsonListQueues(String body) {
        return given()
                .contentType(JSON_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ListQueues")
                .body(body)
                .post("/");
    }
}
