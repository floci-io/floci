package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.BadRequestException;
import software.amazon.awssdk.services.efs.model.DescribeAccessPointsResponse;
import software.amazon.awssdk.services.efs.model.ListTagsForResourceResponse;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EfsPaginationIssueIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listTagsAndAccessPointsHonorMaxResults() {
        String fileSystemId = createFileSystem("first", "second");
        String[] accessPointIds = createAccessPoints(fileSystemId);

        JsonPath tags = given()
                .queryParam("MaxResults", 1)
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(200).extract().jsonPath();
        JsonPath accessPoints = given()
                .queryParam("FileSystemId", fileSystemId)
                .queryParam("MaxResults", 1)
                .get("/2015-02-01/access-points")
                .then().statusCode(200).extract().jsonPath();

        assertAll(
                () -> assertEquals(1, tags.getList("Tags").size(), "ListTagsForResource page size"),
                () -> assertNotNull(tags.getString("NextToken"), "ListTagsForResource continuation"),
                () -> assertEquals(1, accessPoints.getList("AccessPoints").size(), "DescribeAccessPoints page size"),
                () -> assertNotNull(accessPoints.getString("NextToken"), "DescribeAccessPoints continuation"));

        JsonPath nextTags = given()
                .queryParam("MaxResults", 1)
                .queryParam("NextToken", tags.getString("NextToken"))
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(200).extract().jsonPath();
        JsonPath nextAccessPoints = given()
                .queryParam("FileSystemId", fileSystemId)
                .queryParam("MaxResults", 1)
                .queryParam("NextToken", accessPoints.getString("NextToken"))
                .get("/2015-02-01/access-points")
                .then().statusCode(200).extract().jsonPath();

        assertAll(
                () -> assertEquals(1, nextTags.getList("Tags").size()),
                () -> assertNotEquals(tags.getString("Tags[0].Key"), nextTags.getString("Tags[0].Key")),
                () -> assertFalse(nextTags.getMap("$").containsKey("NextToken")),
                () -> assertEquals(1, nextAccessPoints.getList("AccessPoints").size()),
                () -> assertNotEquals(accessPoints.getString("AccessPoints[0].AccessPointId"),
                        nextAccessPoints.getString("AccessPoints[0].AccessPointId")),
                () -> assertFalse(nextAccessPoints.getMap("$").containsKey("NextToken")));

        given().queryParam("NextToken", tags.getString("NextToken"))
                .get("/2015-02-01/resource-tags/" + accessPointIds[0])
                .then().statusCode(400);
        given().queryParam("AccessPointId", accessPointIds[0])
                .queryParam("NextToken", accessPoints.getString("NextToken"))
                .get("/2015-02-01/access-points")
                .then().statusCode(400);
        given().queryParam("NextToken", "not-a-valid-token")
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(400);
        given().queryParam("NextToken", "not-a-valid-token")
                .get("/2015-02-01/access-points")
                .then().statusCode(400);
        given().queryParam("NextToken", "a".repeat(129))
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(400);
        given().queryParam("MaxResults", 0)
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(400);
        given().queryParam("MaxResults", "invalid")
                .get("/2015-02-01/access-points")
                .then().statusCode(400);
        assertEquals(2, given().queryParam("MaxResults", 1000)
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(200).extract().jsonPath().getList("Tags").size());
    }

    @Test
    void longTagKeyStillUsesABoundedToken() {
        String longKey = "a".repeat(128);
        String fileSystemId = createFileSystem(longKey, "z");

        JsonPath first = given().queryParam("MaxResults", 1)
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(200).extract().jsonPath();
        String nextToken = first.getString("NextToken");
        assertAll(
                () -> assertEquals(longKey, first.getString("Tags[0].Key")),
                () -> assertNotNull(nextToken),
                () -> assertTrue(nextToken.length() <= 128));

        JsonPath second = given().queryParam("MaxResults", 1)
                .queryParam("NextToken", nextToken)
                .get("/2015-02-01/resource-tags/" + fileSystemId)
                .then().statusCode(200).extract().jsonPath();
        assertEquals("z", second.getString("Tags[0].Key"));
        assertFalse(second.getMap("$").containsKey("NextToken"));
    }

    @Test
    void awsSdkReadsBothPages() {
        String fileSystemId = createFileSystem("first", "second");
        createAccessPoints(fileSystemId);

        try (EfsClient client = AwsTestClients.create(EfsClient.builder(), "000000000000", Region.US_EAST_1)) {
            ListTagsForResourceResponse firstTags = client.listTagsForResource(
                    request -> request.resourceId(fileSystemId).maxResults(1));
            ListTagsForResourceResponse secondTags = client.listTagsForResource(
                    request -> request.resourceId(fileSystemId).maxResults(1).nextToken(firstTags.nextToken()));
            assertAll(
                    () -> assertEquals(1, firstTags.tags().size()),
                    () -> assertNotNull(firstTags.nextToken()),
                    () -> assertEquals(1, secondTags.tags().size()),
                    () -> assertNotEquals(firstTags.tags().getFirst().key(), secondTags.tags().getFirst().key()),
                    () -> assertNull(secondTags.nextToken()));

            DescribeAccessPointsResponse firstAccessPoints = client.describeAccessPoints(
                    request -> request.fileSystemId(fileSystemId).maxResults(1));
            DescribeAccessPointsResponse secondAccessPoints = client.describeAccessPoints(
                    request -> request.fileSystemId(fileSystemId).maxResults(1)
                            .nextToken(firstAccessPoints.nextToken()));
            assertAll(
                    () -> assertEquals(1, firstAccessPoints.accessPoints().size()),
                    () -> assertNotNull(firstAccessPoints.nextToken()),
                    () -> assertEquals(1, secondAccessPoints.accessPoints().size()),
                    () -> assertNotEquals(firstAccessPoints.accessPoints().getFirst().accessPointId(),
                            secondAccessPoints.accessPoints().getFirst().accessPointId()),
                    () -> assertNull(secondAccessPoints.nextToken()));

            DescribeAccessPointsResponse unfiltered = client.describeAccessPoints(
                    request -> request.maxResults(1));
            assertNotNull(unfiltered.nextToken());
            try (EfsClient otherAccount = AwsTestClients.create(
                    EfsClient.builder(), "111111111111", Region.US_EAST_1);
                 EfsClient otherRegion = AwsTestClients.create(
                         EfsClient.builder(), "000000000000", Region.US_WEST_2)) {
                assertThrows(BadRequestException.class, () -> otherAccount.describeAccessPoints(
                        request -> request.maxResults(1).nextToken(unfiltered.nextToken())));
                assertThrows(BadRequestException.class, () -> otherRegion.describeAccessPoints(
                        request -> request.maxResults(1).nextToken(unfiltered.nextToken())));
            }
        }
    }

    private static String createFileSystem(String firstTagKey, String secondTagKey) {
        return given()
                .contentType("application/json")
                .body("""
                        {"CreationToken":"%s","Tags":[
                          {"Key":"%s","Value":"1"},
                          {"Key":"%s","Value":"2"}
                        ]}
                        """.formatted(UUID.randomUUID(), firstTagKey, secondTagKey))
                .post("/2015-02-01/file-systems")
                .then().statusCode(201).extract().path("FileSystemId");
    }

    private static String[] createAccessPoints(String fileSystemId) {
        String token = UUID.randomUUID().toString();
        String[] accessPointIds = new String[2];
        for (int i = 0; i < accessPointIds.length; i++) {
            accessPointIds[i] = given().contentType("application/json")
                    .body("""
                            {"ClientToken":"%s","FileSystemId":"%s"}
                            """.formatted(token + "-" + i, fileSystemId))
                    .post("/2015-02-01/access-points")
                    .then().statusCode(200).extract().path("AccessPointId");
        }
        return accessPointIds;
    }
}
