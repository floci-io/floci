package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for the SES V2 import-job endpoints: {@code POST /v2/email/import-jobs}
 * (CreateImportJob), {@code GET /v2/email/import-jobs/{JobId}} (GetImportJob) and
 * {@code POST /v2/email/import-jobs/list} (ListImportJobs), end to end through the local S3
 * emulation into the suppression list and a contact list. The job runs on a background worker as
 * on AWS, so the tests poll GetImportJob until it is terminal. Uses its own region so the
 * suppression list and the one-per-region contact list do not collide with other SES tests.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesImportJobV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-3/ses/aws4_request";
    private static final String BUCKET = "ses-import-job-it";
    private static final String LIST = "import-job-list";

    private static String suppressionJobId;
    private static String contactJobId;

    @Test
    @Order(0)
    void setupBucketAndContactList() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"ContactListName":"%s","Topics":[
                      {"TopicName":"Sports","DisplayName":"Sports","DefaultSubscriptionStatus":"OPT_OUT"},
                      {"TopicName":"Cycling","DisplayName":"Cycling","DefaultSubscriptionStatus":"OPT_IN"}]}
                    """.formatted(LIST))
        .when().post("/v2/email/contact-lists").then().statusCode(200);
    }

    @Test
    @Order(1)
    void createImportJob_suppressionCsv_runsToCompletedAndFillsTheList() {
        putObject("suppress.csv", "alice@example.com,BOUNCE\nbob@example.com,COMPLAINT\ncarol@example.com,SPAM\n");

        suppressionJobId = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"ImportDataSource":{"S3Url":"s3://%s/suppress.csv","DataFormat":"CSV"},
                     "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"PUT"}}}
                    """.formatted(BUCKET))
        .when().post("/v2/email/import-jobs").then().statusCode(200)
                .body("JobId", notNullValue())
                .extract().jsonPath().getString("JobId");

        assertEquals("COMPLETED", pollUntilTerminal(suppressionJobId));
        given().header("Authorization", AUTH)
        .when().get("/v2/email/import-jobs/" + suppressionJobId).then().statusCode(200)
                .body("JobId", equalTo(suppressionJobId))
                .body("JobStatus", equalTo("COMPLETED"))
                .body("ImportDataSource.S3Url", equalTo("s3://" + BUCKET + "/suppress.csv"))
                .body("ImportDataSource.DataFormat", equalTo("CSV"))
                .body("ImportDestination.SuppressionListDestination.SuppressionListImportAction", equalTo("PUT"))
                .body("ImportDestination.ContactListDestination", nullValue())
                .body("ProcessedRecordsCount", equalTo(3))
                .body("FailedRecordsCount", equalTo(1))
                .body("CreatedTimestamp", notNullValue())
                .body("CompletedTimestamp", notNullValue())
                .body("FailureInfo", nullValue());

        given().header("Authorization", AUTH)
        .when().get("/v2/email/suppression/addresses").then().statusCode(200)
                .body("SuppressedDestinationSummaries.EmailAddress",
                        hasItems("alice@example.com", "bob@example.com"))
                .body("SuppressedDestinationSummaries", hasSize(2));
    }

    @Test
    @Order(2)
    void createImportJob_contactJson_addsContactsWithPreferences() {
        putObject("contacts.json", """
                {"emailAddress":"carol@example.com","unsubscribeAll":false,"attributesData":"{\\"Name\\":\\"Carol\\"}","topicPreferences":[{"topicName":"Sports","subscriptionStatus":"OPT_IN"}]}
                {"emailAddress":"dave@example.com","unsubscribeAll":true}
                """);

        contactJobId = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"ImportDataSource":{"S3Url":"s3://%s/contacts.json","DataFormat":"JSON"},
                     "ImportDestination":{"ContactListDestination":{"ContactListName":"%s","ContactListImportAction":"PUT"}}}
                    """.formatted(BUCKET, LIST))
        .when().post("/v2/email/import-jobs").then().statusCode(200)
                .extract().jsonPath().getString("JobId");

        assertEquals("COMPLETED", pollUntilTerminal(contactJobId));
        given().header("Authorization", AUTH)
        .when().get("/v2/email/import-jobs/" + contactJobId).then().statusCode(200)
                .body("ImportDestination.ContactListDestination.ContactListName", equalTo(LIST))
                .body("ImportDestination.ContactListDestination.ContactListImportAction", equalTo("PUT"))
                .body("ProcessedRecordsCount", equalTo(2))
                .body("FailedRecordsCount", equalTo(0));

        given().header("Authorization", AUTH)
        .when().get("/v2/email/contact-lists/" + LIST + "/contacts/carol@example.com").then().statusCode(200)
                .body("AttributesData", equalTo("{\"Name\":\"Carol\"}"))
                .body("TopicPreferences[0].TopicName", equalTo("Sports"))
                .body("TopicPreferences[0].SubscriptionStatus", equalTo("OPT_IN"));
        given().header("Authorization", AUTH)
        .when().get("/v2/email/contact-lists/" + LIST + "/contacts/dave@example.com").then().statusCode(200)
                .body("UnsubscribeAll", equalTo(true));
    }

    @Test
    @Order(3)
    void listImportJobs_returnsSummariesAndFiltersByDestinationType() {
        given().contentType("application/json").header("Authorization", AUTH)
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", hasItems(suppressionJobId, contactJobId))
                .body("ImportJobs.find { it.JobId == '" + contactJobId + "' }.JobStatus", equalTo("COMPLETED"))
                .body("ImportJobs.find { it.JobId == '" + contactJobId + "' }.ImportDataSource", nullValue())
                .body("NextToken", nullValue());

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ImportDestinationType\":\"SUPPRESSION_LIST\",\"PageSize\":10}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", hasItems(suppressionJobId))
                .body("ImportJobs.JobId", not(hasItems(contactJobId)));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ImportDestinationType\":\"EXPORT\"}")
        .when().post("/v2/email/import-jobs/list").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(4)
    void createImportJob_missingObject_failsTheJob() {
        String jobId = given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"ImportDataSource":{"S3Url":"s3://%s/does-not-exist.csv","DataFormat":"CSV"},
                     "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"DELETE"}}}
                    """.formatted(BUCKET))
        .when().post("/v2/email/import-jobs").then().statusCode(200)
                .extract().jsonPath().getString("JobId");

        assertEquals("FAILED", pollUntilTerminal(jobId));
        given().header("Authorization", AUTH)
        .when().get("/v2/email/import-jobs/" + jobId).then().statusCode(200)
                .body("FailureInfo.ErrorMessage", containsString("does not exist"))
                .body("ProcessedRecordsCount", equalTo(0));
    }

    @Test
    @Order(5)
    void createImportJob_validationErrors() {
        String badFormat = """
                {"ImportDataSource":{"S3Url":"s3://%s/x.csv","DataFormat":"XML"},
                 "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"PUT"}}}
                """.formatted(BUCKET);
        String bothDestinations = """
                {"ImportDataSource":{"S3Url":"s3://%s/x.csv","DataFormat":"CSV"},
                 "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"PUT"},
                                      "ContactListDestination":{"ContactListName":"%s","ContactListImportAction":"PUT"}}}
                """.formatted(BUCKET, LIST);
        String noDataSource = """
                {"ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"PUT"}}}
                """;
        String unknownList = """
                {"ImportDataSource":{"S3Url":"s3://%s/x.csv","DataFormat":"CSV"},
                 "ImportDestination":{"ContactListDestination":{"ContactListName":"nope","ContactListImportAction":"PUT"}}}
                """.formatted(BUCKET);

        String trailingTokens = """
                {"ImportDataSource":{"S3Url":"s3://%s/x.csv","DataFormat":"CSV"},
                 "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"PUT"}}} garbage
                """.formatted(BUCKET);

        for (String body : new String[] {badFormat, bothDestinations, noDataSource, trailingTokens}) {
            given().contentType("application/json").header("Authorization", AUTH).body(body)
            .when().post("/v2/email/import-jobs").then().statusCode(400)
                    .body("__type", equalTo("BadRequestException"));
        }
        given().contentType("application/json").header("Authorization", AUTH)
        .when().post("/v2/email/import-jobs").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
        given().contentType("application/json").header("Authorization", AUTH).body(unknownList)
        .when().post("/v2/email/import-jobs").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(6)
    void getImportJob_unknownId_isNotFound() {
        given().header("Authorization", AUTH)
        .when().get("/v2/email/import-jobs/00000000-0000-0000-0000-000000000000").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void importJobsAndTheirRecordsAreScopedToTheCallingAccount() {
        // A 12-digit access key id in the credential becomes the account (AccountContextFilter), so
        // these two headers are two accounts hitting the same region, neither of them the default
        // account the rest of this class runs as.
        String authA = accountAuth("111111111111", "ses");
        String authB = accountAuth("222222222222", "ses");
        // The credential's service scope routes the request, so the S3 calls need an s3-scoped one.
        String s3AuthA = accountAuth("111111111111", "s3");
        String bucketA = BUCKET + "-a";

        given().header("Authorization", s3AuthA).when().put("/" + bucketA).then().statusCode(200);
        given().header("Authorization", s3AuthA).contentType("text/plain").body("account-a@example.com,BOUNCE\n")
        .when().put("/" + bucketA + "/suppress.csv").then().statusCode(200);

        String jobA = given().contentType("application/json").header("Authorization", authA)
                .body("""
                    {"ImportDataSource":{"S3Url":"s3://%s/suppress.csv","DataFormat":"CSV"},
                     "ImportDestination":{"SuppressionListDestination":{"SuppressionListImportAction":"PUT"}}}
                    """.formatted(bucketA))
        .when().post("/v2/email/import-jobs").then().statusCode(200)
                .extract().jsonPath().getString("JobId");

        // Reaching COMPLETED under account A also proves the worker's terminal write landed in A's
        // partition: RequestScopes.runAs carries the account onto the worker thread, and without it
        // the write would go to the default account and this poll would never leave PROCESSING.
        assertEquals("COMPLETED", pollUntilTerminal(jobA, authA));

        given().header("Authorization", authB)
        .when().get("/v2/email/import-jobs/" + jobA).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
        given().contentType("application/json").header("Authorization", authB)
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", not(hasItems(jobA)));
        given().contentType("application/json").header("Authorization", authA)
        .when().post("/v2/email/import-jobs/list").then().statusCode(200)
                .body("ImportJobs.JobId", hasItems(jobA));

        // The imported rows are account-scoped too, not just the job record.
        given().header("Authorization", authA)
        .when().get("/v2/email/suppression/addresses").then().statusCode(200)
                .body("SuppressedDestinationSummaries.EmailAddress", hasItems("account-a@example.com"));
        given().header("Authorization", authB)
        .when().get("/v2/email/suppression/addresses").then().statusCode(200)
                .body("SuppressedDestinationSummaries.EmailAddress", not(hasItems("account-a@example.com")));

        given().header("Authorization", authA)
        .when().delete("/v2/email/suppression/addresses/account-a@example.com").then().statusCode(200);
        given().header("Authorization", s3AuthA).when().delete("/" + bucketA + "/suppress.csv").then().statusCode(204);
        given().header("Authorization", s3AuthA).when().delete("/" + bucketA).then().statusCode(204);
    }

    private static String accountAuth(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/eu-west-3/" + service + "/aws4_request";
    }

    @Test
    @Order(8)
    void cleanup() {
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/contact-lists/" + LIST).then().statusCode(200);
        for (String address : new String[] {"alice@example.com", "bob@example.com"}) {
            given().header("Authorization", AUTH)
            .when().delete("/v2/email/suppression/addresses/" + address).then().statusCode(200);
        }
    }

    private static void putObject(String key, String content) {
        given().contentType("text/plain").body(content)
        .when().put("/" + BUCKET + "/" + key).then().statusCode(200);
    }

    private static String pollUntilTerminal(String jobId) {
        return pollUntilTerminal(jobId, AUTH);
    }

    private static String pollUntilTerminal(String jobId, String auth) {
        Supplier<Response> call = () -> given().header("Authorization", auth)
                .when().get("/v2/email/import-jobs/" + jobId);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String status = call.get().then().statusCode(200).extract().jsonPath().getString("JobStatus");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            sleep(100);
        }
        return "TIMEOUT";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
