package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class GlueCrawlerRunIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/my-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue." + action)
                .body(body)
        .when().post("/")
        .then();
    }

    @Test
    void crawlRunsToCompletionAndItsHistoryGoesWithTheCrawler() {
        String name = "crawl-" + UUID.randomUUID().toString().substring(0, 8);
        String byName = "{ \"Name\": \"" + name + "\" }";
        call("CreateCrawler", """
                { "Name": "%s", "Role": "%s", "Targets": { "S3Targets": [ { "Path": "s3://raw/data" } ] } }
                """.formatted(name, ROLE)).statusCode(200);

        call("StartCrawler", byName).statusCode(200);

        call("GetCrawler", byName)
                .statusCode(200)
                .body("Crawler.State", equalTo("READY"))
                .body("Crawler.LastCrawl.Status", equalTo("SUCCEEDED"));

        call("ListCrawlers", "{}")
                .statusCode(200)
                .body("CrawlerNames", hasItem(name));

        call("StopCrawler", byName)
                .statusCode(400)
                .body("__type", equalTo("CrawlerNotRunningException"));

        call("DeleteCrawler", byName).statusCode(200);

        call("StartCrawler", byName)
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }
}
