package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Paging of the v2 {@code ListImportJobs} list: oldest job first, the Smithy validation messages
 * borrowed from the tenant lists, the empty-token error and the filter being validated first. Runs
 * in its own region so the jobs created here are the whole list; the test-scope ticking clock
 * separates their creation stamps. The jobs delete addresses that were never suppressed, so they
 * leave no state behind whatever their outcome.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesImportJobPagingIntegrationTest {

    private static final String REGION = "eu-central-2";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION + "/ses/aws4_request";
    private static final String BUCKET = "ses-import-job-paging-it";
    private static final String PAGE_SIZE_BELOW_ONE = "Value '0' at 'pageSize' failed to satisfy constraint: "
            + "Member must have value greater than or equal to 1";
    private static final String PAGE_SIZE_ABOVE_BOUND = "Value '101' at 'pageSize' failed to satisfy constraint: "
            + "Member must have value less than or equal to 100";
    private static final String EMPTY_TOKEN = "Value '' at 'nextToken' failed to satisfy constraint: "
            + "Member must have length greater than or equal to 1";

    private static final List<String> created = new ArrayList<>();

    private static RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    @Test
    @Order(1)
    void createThreeJobs() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().contentType("text/plain").body("never-suppressed@example.com\n")
        .when().put("/" + BUCKET + "/delete.csv").then().statusCode(200);
        for (int i = 0; i < 3; i++) {
            created.add(v2().body("""
                    {"ImportDataSource":{"S3Url":"s3://%s/delete.csv","DataFormat":"CSV"},
                     "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"DELETE"}}}
                    """.formatted(BUCKET))
            .when().post("/v2/email/import-jobs").then().statusCode(200)
                    .extract().path("JobId"));
        }
    }

    @Test
    @Order(2)
    void walksOldestFirstAndEndsOnANullToken() {
        String token = v2().body("{\"PageSize\":1}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", contains(created.get(0)))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");

        v2().body("{\"PageSize\":5,\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", contains(created.get(1), created.get(2)))
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(3)
    void outOfRangeSizesAndBadTokensAnswerTheSmithyMessages() {
        v2().body("{\"PageSize\":0}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: " + PAGE_SIZE_BELOW_ONE));
        v2().body("{\"PageSize\":101}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: " + PAGE_SIZE_ABOVE_BOUND));
        v2().body("{\"NextToken\":\"garbage\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
        v2().body("{\"NextToken\":\"\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: " + EMPTY_TOKEN));
        v2().body("{\"PageSize\":0,\"NextToken\":\"\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: " + EMPTY_TOKEN + "; " + PAGE_SIZE_BELOW_ONE));
    }

    @Test
    @Order(4)
    void aTokenIsBoundToItsFilter() {
        String token = v2().body("{\"ImportDestinationType\":\"SUPPRESSION_LIST\",\"PageSize\":1}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(200).extract().path("NextToken");

        v2().body("{\"ImportDestinationType\":\"SUPPRESSION_LIST\",\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", contains(created.get(1), created.get(2)));
        v2().body("{\"NextToken\":\"" + token + "\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("message", equalTo("Invalid Next Token"));
    }

    @Test
    @Order(5)
    void theFilterIsValidatedBeforeThePage() {
        v2().body("{\"ImportDestinationType\":\"EXPORT\",\"PageSize\":0}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'importDestinationType' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[SUPPRESSION_LIST, CONTACT_LIST]"));
    }

    @Test
    @Order(6)
    void cleanup() {
        given().when().delete("/" + BUCKET + "/delete.csv").then().statusCode(204);
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}
