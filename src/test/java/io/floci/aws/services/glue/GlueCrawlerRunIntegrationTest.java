package io.floci.aws.services.glue;

import io.floci.aws.testing.GlueRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class GlueCrawlerRunIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT_ID + ":role/my-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return GlueRequests.call(action, ACCOUNT_ID, REGION, body);
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
