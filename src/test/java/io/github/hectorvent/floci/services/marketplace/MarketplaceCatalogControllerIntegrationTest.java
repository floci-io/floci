package io.github.hectorvent.floci.services.marketplace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MarketplaceCatalogControllerIntegrationTest {
    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void changeSetCreatesAndDescribesEntity() {
        String body = "{\"Catalog\":\"AWSMarketplace\",\"ChangeSet\":[{\"ChangeType\":\"CreateProduct\",\"Entity\":{\"Type\":\"SaaSProduct@1.0\",\"Identifier\":\"@1\"},\"DetailsDocument\":{\"ProductTitle\":\"Local product\"}}]}";
        String id = given().contentType("application/json").header("Authorization", auth())
                .body(body).post("/StartChangeSet").then().statusCode(200)
                .body("ChangeSetId", notNullValue())
                .body("ChangeSetArn", containsString(":000000000000:AWSMarketplace/ChangeSet/"))
                .extract().path("ChangeSetId");
        var described = given().header("Authorization", auth())
                .get("/DescribeChangeSet?catalog=AWSMarketplace&changeSetId=" + id)
                .then().statusCode(200).body("Status", equalTo("SUCCEEDED")).extract().response();
        String entityId = described.path("ChangeSet[0].Entity.Identifier");
        given().header("Authorization", auth())
                .get("/DescribeEntity?catalog=AWSMarketplace&entityId=" + entityId)
                .then().statusCode(200)
                .body("EntityIdentifier", equalTo(entityId))
                .body("EntityArn", containsString(":000000000000:AWSMarketplace/SaaSProduct/"))
                .body("DetailsDocument.ProductTitle", equalTo("Local product"));
    }

    @Test
    void cancelAndValidationMatchAwsErrorShapes() {
        String id = given().contentType("application/json").header("Authorization", auth())
                .body("{\"Catalog\":\"AWSMarketplace\",\"ChangeSet\":[{\"ChangeType\":\"CreateOffer\",\"Entity\":{\"Type\":\"Offer@1.0\",\"Identifier\":\"@1\"},\"DetailsDocument\":{}}]}")
                .post("/StartChangeSet").then().statusCode(200).extract().path("ChangeSetId");
        given().header("Authorization", auth())
                .patch("/CancelChangeSet?catalog=AWSMarketplace&changeSetId=" + id)
                .then().statusCode(200).body("ChangeSetId", equalTo(id));
        given().contentType("application/json").header("Authorization", auth()).body("{}")
                .post("/ListEntities").then().statusCode(422)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void rejectsUnsupportedChangeTypeBeforeMutatingCatalogState() {
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"Catalog\":\"AWSMarketplace\",\"ChangeSet\":[{\"ChangeType\":\"CreatProduct\",\"Entity\":{\"Type\":\"SaaSProduct@1.0\"},\"DetailsDocument\":{}}]}")
                .post("/StartChangeSet").then().statusCode(422)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void tagsAndPoliciesPersistOnEntity() {
        String cs = given().contentType("application/json").header("Authorization", auth())
                .body("{\"Catalog\":\"AWSMarketplace\",\"ChangeSet\":[{\"ChangeType\":\"CreateProduct\",\"Entity\":{\"Type\":\"SaaSProduct@1.0\",\"Identifier\":\"@1\"},\"DetailsDocument\":{}}]}")
                .post("/StartChangeSet").then().statusCode(200).extract().path("ChangeSetId");
        var response = given().header("Authorization", auth())
                .get("/DescribeChangeSet?catalog=AWSMarketplace&changeSetId=" + cs)
                .then().extract().response();
        String arn = response.path("ChangeSet[0].EntityArn");
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .post("/TagResource").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .post("/ListTagsForResource").then().statusCode(200)
                .body("Tags[0].Key", equalTo("env"));
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"ResourceArn\":\"" + arn + "\",\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\"}\"}")
                .post("/PutResourcePolicy").then().statusCode(200);
        given().header("Authorization", auth())
                .get("/GetResourcePolicy?resourceArn=" + arn).then().statusCode(200)
                .body("Policy", containsString("2012-10-17"));
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/us-east-1/aws-marketplace/aws4_request";
    }
}
