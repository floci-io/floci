package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GetApiKeys} paging.
 *
 * <p>The API reference documents {@code limit} (default 25, a page capped at 500) and
 * {@code position} for this operation, and {@code GetUsage} and {@code GetDocumentationParts}
 * already page over key collections the same way: the token is the id of the last entry already
 * returned, so a page resumes after it rather than at a positional offset a concurrent create
 * could shift.
 *
 * <p>Each test signs for a region of its own, because a {@code @QuarkusTest} shares one store
 * across the class and the other API Gateway suites create keys in the regions they sign for.
 * A shared region would let one test's keys land in another's page and decide its size.
 */
@QuarkusTest
class ApiGatewayGetApiKeysPaginationIntegrationTest {

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=test/20261113/" + region
                + "/apigateway/aws4_request, SignedHeaders=host, Signature=test";
    }

    /** Creates a key whose id is {@code pagekey-NN}, so the id order is the creation order. */
    private static void createKey(String region, int index) {
        String id = String.format("pagekey-%02d", index);
        given().header("Authorization", auth(region)).contentType(ContentType.JSON)
                .body("{\"name\":\"" + id + "\",\"value\":\"" + id + "\",\"generateDistinctId\":false}")
                .when().post("/apikeys")
                .then().statusCode(201)
                .body("id", equalTo(id));
    }

    private static void createKeys(String region, int count) {
        for (int i = 0; i < count; i++) {
            createKey(region, i);
        }
    }

    private static ValidatableResponse page(String region, String query) {
        return given().header("Authorization", auth(region))
                .when().get("/apikeys" + query)
                .then().statusCode(200);
    }

    private static List<String> pageIds(String region, String query) {
        return page(region, query).extract().path("item.id");
    }

    private static String pagePosition(String region, String query) {
        return page(region, query).extract().path("position");
    }

    @Test
    void aPageDefaultsToTwentyFiveKeys() {
        String region = "ap-south-2";
        createKeys(region, 27);

        given().header("Authorization", auth(region))
                .when().get("/apikeys")
                .then().statusCode(200)
                .body("item", hasSize(25))
                .body("item[0].id", equalTo("pagekey-00"))
                .body("item[24].id", equalTo("pagekey-24"))
                .body("position", equalTo("pagekey-24"));
    }

    @Test
    void thePositionResumesAfterTheLastKeyReturned() {
        String region = "ap-southeast-3";
        createKeys(region, 27);

        List<String> first = pageIds(region, "?limit=10");
        assertEquals(10, first.size());
        assertEquals("pagekey-00", first.get(0));

        String position = pagePosition(region, "?limit=10");
        assertEquals("pagekey-09", position);

        List<String> second = pageIds(region, "?limit=10&position=" + position);
        assertEquals(10, second.size());
        assertTrue(second.stream().noneMatch(first::contains));

        List<String> third = pageIds(region, "?limit=10&position=" + second.get(second.size() - 1));
        assertEquals(7, third.size());
        assertEquals("pagekey-26", third.get(6));
    }

    @Test
    void aReusedPositionReturnsTheSamePage() {
        String region = "ap-southeast-4";
        createKeys(region, 12);

        String position = pagePosition(region, "?limit=5");
        assertEquals("pagekey-04", position);

        List<String> once = pageIds(region, "?limit=5&position=" + position);
        List<String> twice = pageIds(region, "?limit=5&position=" + position);
        assertEquals(once, twice);
        assertEquals("pagekey-05", once.get(0));
    }

    @Test
    void aPageThatEndsTheListCarriesNoPosition() {
        String region = "ap-southeast-5";
        createKeys(region, 3);

        given().header("Authorization", auth(region))
                .when().get("/apikeys?limit=2")
                .then().statusCode(200)
                .body("item", hasSize(2))
                .body("position", equalTo("pagekey-01"));

        given().header("Authorization", auth(region))
                .when().get("/apikeys?limit=2&position=pagekey-01")
                .then().statusCode(200)
                .body("item", hasSize(1))
                .body("item[0].id", equalTo("pagekey-02"))
                .body("position", nullValue());
    }

    @Test
    void anUnrecognisedPositionIsRejected() {
        String region = "ap-southeast-7";
        createKey(region, 0);

        given().header("Authorization", auth(region))
                .when().get("/apikeys?position=nonsense")
                .then().statusCode(400)
                .body(containsString("Invalid position parameter"));
    }

    @Test
    void aLimitBelowOneIsRejected() {
        String region = "il-central-1";
        createKey(region, 0);

        // Real API Gateway answers a negative limit with an InternalFailure. That is a fault, not a
        // contract, so a page size below one is rejected rather than reproducing a 500.
        for (String limit : new String[] {"0", "-1"}) {
            given().header("Authorization", auth(region))
                    .when().get("/apikeys?limit=" + limit)
                    .then().statusCode(400)
                    .body(containsString("Invalid limit parameter"));
        }
    }

    @Test
    void aLimitAboveTheDocumentedMaximumIsAccepted() {
        String region = "ap-east-2";
        createKey(region, 0);

        // Probed against real API Gateway for GetUsage: every value from 500 up to
        // Integer.MAX_VALUE is accepted without error, so the documented maximum of 500 is not a
        // rejection boundary.
        for (String limit : new String[] {"500", "501", "100000", "2147483647"}) {
            given().header("Authorization", auth(region))
                    .when().get("/apikeys?limit=" + limit)
                    .then().statusCode(200)
                    .body("item", hasSize(1));
        }
    }

    @Test
    void aPageStillProjectsValuesOnlyWhenAsked() {
        String region = "mx-central-1";
        createKeys(region, 3);

        given().header("Authorization", auth(region))
                .when().get("/apikeys?limit=2")
                .then().statusCode(200)
                .body("item", hasSize(2))
                .body("item[0].value", nullValue())
                .body("item[1].value", nullValue());

        given().header("Authorization", auth(region))
                .when().get("/apikeys?limit=2&includeValues=true")
                .then().statusCode(200)
                .body("item", hasSize(2))
                .body("item[0].value", equalTo("pagekey-00"))
                .body("item[1].value", equalTo("pagekey-01"));
    }

    @Test
    void keysOfAnotherRegionAreNeitherPagedInNorPagedOut() {
        String region = "eu-south-2";
        createKeys(region, 2);

        List<String> otherRegion = new ArrayList<>(given()
                .header("Authorization", auth("ca-west-1"))
                .when().get("/apikeys")
                .then().statusCode(200)
                .extract().path("item.id"));

        assertTrue(otherRegion.stream().noneMatch(id -> id.startsWith("pagekey-")));
        assertEquals(List.of("pagekey-00", "pagekey-01"), pageIds(region, ""));
        assertEquals("pagekey-00", pagePosition(region, "?limit=1"));
    }

    @Test
    void aKeyCreatedAfterTheFirstPageIsReachableThroughTheToken() {
        String region = "me-central-1";
        createKeys(region, 3);

        String position = pagePosition(region, "?limit=2");
        assertEquals("pagekey-01", position);

        createKey(region, 50);

        assertEquals(List.of("pagekey-02", "pagekey-50"), pageIds(region, "?limit=25&position=" + position));

        given().header("Authorization", auth(region))
                .when().get("/apikeys?limit=1&position=pagekey-50")
                .then().statusCode(200)
                .body("item", hasSize(0))
                .body("position", nullValue());
    }
}
