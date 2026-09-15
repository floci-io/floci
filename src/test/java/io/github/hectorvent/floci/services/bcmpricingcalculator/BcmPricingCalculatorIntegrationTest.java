package io.github.hectorvent.floci.services.bcmpricingcalculator;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class BcmPricingCalculatorIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "AWSBCMPricingCalculator.";

    @BeforeAll
    static void setup() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void workloadEstimateLifecyclePricesUsage() {
        String id = given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body("""
                        {"name":"estimate-one","clientToken":"estimate-token-1","rateType":"BEFORE_DISCOUNTS"}
                        """)
            .when().post("/")
            .then().statusCode(200)
                .body("status", equalTo("VALID"))
                .body("createdAt", instanceOf(Number.class))
                .body("expiresAt", instanceOf(Number.class))
                .body("rateTimestamp", instanceOf(Number.class))
                .body("costCurrency", equalTo("USD"))
                .extract().path("id");

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "BatchCreateWorkloadEstimateUsage")
                .body("""
                        {"workloadEstimateId":"%s","clientToken":"usage-token-1","usage":[{
                          "serviceCode":"AmazonEC2","usageType":"BoxUsage:t3.micro","operation":"",
                          "key":"ec2a","usageAccountId":"000000000000","group":"compute","amount":730
                        }]}
                        """.formatted(id))
            .when().post("/")
            .then().statusCode(200)
                .body("errors", empty())
                .body("items.size()", equalTo(1))
                .body("items[0].status", equalTo("VALID"))
                .body("items[0].cost", equalTo(7.592f));

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "GetWorkloadEstimate")
                .body("{\"identifier\":\"" + id + "\"}")
            .when().post("/")
            .then().statusCode(200)
                .body("status", equalTo("VALID"))
                .body("totalCost", equalTo(7.592f));

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "DeleteWorkloadEstimate")
                .body("{\"identifier\":\"" + id + "\"}")
            .when().post("/")
            .then().statusCode(200);
    }

    @Test
    void createIsIdempotentByClientToken() {
        String body = "{\"name\":\"idem-estimate\",\"clientToken\":\"same-token\"}";
        String first = given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body(body).when().post("/").then().statusCode(200).extract().path("id");
        String second = given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body(body).when().post("/").then().statusCode(200).extract().path("id");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }
    @Test
    void tokenConflictAndIdempotentDeleteMatchAwsContract() {
        String firstBody = "{\"name\":\"token-one\",\"clientToken\":\"conflict-token\"}";
        String id = given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body(firstBody).when().post("/").then().statusCode(200).extract().path("id");

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body("{\"name\":\"token-two\",\"clientToken\":\"conflict-token\"}")
            .when().post("/").then().statusCode(400).body("__type", containsString("ConflictException"));

        String deleteBody = "{\"identifier\":\"" + id + "\"}";
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "DeleteWorkloadEstimate")
                .body(deleteBody).when().post("/").then().statusCode(200);
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "DeleteWorkloadEstimate")
                .body(deleteBody).when().post("/").then().statusCode(200);
    }

    @Test
    void estimatesAreAccountIsolated() {
        String accountA = "AWS4-HMAC-SHA256 Credential=111111111111/20260911/us-east-1/bcm-pricing-calculator/aws4_request";
        String accountB = "AWS4-HMAC-SHA256 Credential=222222222222/20260911/us-east-1/bcm-pricing-calculator/aws4_request";
        String id = given().contentType(CONTENT_TYPE).header("Authorization", accountA)
                .header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body("{\"name\":\"account-a\"}")
            .when().post("/").then().statusCode(200).extract().path("id");

        given().contentType(CONTENT_TYPE).header("Authorization", accountB)
                .header("X-Amz-Target", TARGET + "GetWorkloadEstimate")
                .body("{\"identifier\":\"" + id + "\"}")
            .when().post("/").then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void createAcceptsUpToTwoHundredTagsAndRejectsMore() {
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            tags.put("tag" + i, "value");
        }
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body(java.util.Map.of("name", "two-hundred-tags", "tags", tags))
            .when().post("/").then().statusCode(200);

        tags.put("tag200", "value");
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + "CreateWorkloadEstimate")
                .body(java.util.Map.of("name", "too-many-tags", "tags", tags))
            .when().post("/").then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

}
