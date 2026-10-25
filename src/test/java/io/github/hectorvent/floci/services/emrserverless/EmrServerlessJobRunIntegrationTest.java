package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.EmrServerlessRequests;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmrServerlessJobRunIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-job";
    private static final RestAssuredConfig DECIMAL_NUMBERS = RestAssuredConfig.config()
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));

    @Inject
    MutableClock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification givenReq() {
        return EmrServerlessRequests.emrServerless("us-east-1");
    }

    private static String createApplication(String extra) {
        return createApplicationOn("emr-7.5.0", extra);
    }

    private static String createApplicationOn(String releaseLabel, String extra) {
        return givenReq()
                .body("""
                        {"name": "jobs-%s", "releaseLabel": "%s", "type": "SPARK", "clientToken": "%s"%s}
                        """.formatted(UUID.randomUUID(), releaseLabel, UUID.randomUUID(), extra))
                .when().post("/applications")
                .then().statusCode(200)
                .extract().path("applicationId");
    }

    private static String sparkRun(String clientToken, String extra) {
        return """
                {"clientToken": "%s", "executionRoleArn": "%s",
                 "jobDriver": {"sparkSubmit": {"entryPoint": "s3://bucket/jobs/app.jar",
                                               "entryPointArguments": ["s3://bucket/in"],
                                               "sparkSubmitParameters": "--class example.Main"}}%s}
                """.formatted(clientToken, ROLE, extra);
    }

    private static Response startJobRun(String applicationId, String body) {
        return givenReq().body(body).when().post("/applications/" + applicationId + "/jobruns");
    }

    private static String startedRunId(String applicationId, String body) {
        return startJobRun(applicationId, body).then().statusCode(200).extract().path("jobRunId");
    }

    @Test
    void startJobRunAutoStartsTheApplicationAndSucceedsByDefault() {
        String applicationId = createApplication("");
        givenReq().when().get("/applications/" + applicationId)
                .then().statusCode(200).body("application.state", equalTo("CREATED"));

        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ", \"name\": \"nightly\""))
                .then().statusCode(200)
                .body("applicationId", equalTo(applicationId))
                .body("jobRunId", matchesPattern("[0-9a-z]{16}"))
                .body("arn", matchesPattern("arn:aws:emr-serverless:us-east-1:000000000000:/applications/"
                        + applicationId + "/jobruns/[0-9a-z]{16}"));

        givenReq().when().get("/applications/" + applicationId)
                .then().statusCode(200).body("application.state", equalTo("STARTED"));
    }

    @Test
    void getJobRunReportsTheRunAsGetJobRunShapesIt() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(),
                ", \"name\": \"nightly\", \"tags\": {\"team\": \"data\"}, \"mode\": \"BATCH\""));

        givenReq().when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(200)
                .body("jobRun.applicationId", equalTo(applicationId))
                .body("jobRun.jobRunId", equalTo(jobRunId))
                .body("jobRun.name", equalTo("nightly"))
                .body("jobRun.arn", endsWith("/applications/" + applicationId + "/jobruns/" + jobRunId))
                .body("jobRun.createdBy", equalTo("arn:aws:iam::000000000000:root"))
                .body("jobRun.createdAt", instanceOf(Number.class))
                .body("jobRun.updatedAt", instanceOf(Number.class))
                .body("jobRun.executionRole", equalTo(ROLE))
                .body("jobRun.state", equalTo("SUCCESS"))
                .body("jobRun.stateDetails", equalTo(""))
                .body("jobRun.releaseLabel", equalTo("emr-7.5.0"))
                .body("jobRun.jobDriver.sparkSubmit.entryPoint", equalTo("s3://bucket/jobs/app.jar"))
                .body("jobRun.jobDriver.sparkSubmit.entryPointArguments", contains("s3://bucket/in"))
                .body("jobRun.jobDriver.sparkSubmit.sparkSubmitParameters", equalTo("--class example.Main"))
                .body("jobRun.tags.team", equalTo("data"))
                .body("jobRun.mode", equalTo("BATCH"))
                .body("jobRun.executionTimeoutMinutes", equalTo(720))
                .body("jobRun.totalExecutionDurationSeconds", equalTo(0))
                .body("jobRun.startedAt", instanceOf(Number.class))
                .body("jobRun.endedAt", instanceOf(Number.class))
                .body("jobRun.attempt", equalTo(1))
                .body("jobRun.attemptCreatedAt", instanceOf(Number.class))
                .body("jobRun.attemptUpdatedAt", instanceOf(Number.class))
                .body("jobRun", not(hasKey("hive")));
    }

    @Test
    void timestampsAreEpochSecondsWithMilliseconds() {
        String applicationId = createApplication("");
        clock.advance(Duration.ofMillis(250));
        Instant submittedAt = clock.instant();
        String jobRunId = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        BigDecimal createdAt = givenReq().config(DECIMAL_NUMBERS)
                .when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(200).extract().path("jobRun.createdAt");
        // The test clock moves a millisecond on every read, so the run was created a little later.
        BigDecimal submitted = BigDecimal.valueOf(submittedAt.toEpochMilli(), 3);
        assertTrue(createdAt.compareTo(submitted) >= 0 && createdAt.compareTo(submitted.add(BigDecimal.ONE)) < 0,
                "createdAt " + createdAt + " is not just after " + submitted);
        assertTrue(createdAt.stripTrailingZeros().scale() <= 3, "createdAt " + createdAt + " is finer than milliseconds");
    }

    @Test
    void runWithoutTagsReportsAnEmptyTagMap() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        givenReq().when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(200)
                .body("jobRun.tags", anEmptyMap())
                .body("jobRun", not(hasKey("mode")));
    }

    @Test
    void repeatedClientTokenReturnsTheOriginalRun() {
        String applicationId = createApplication("");
        String body = sparkRun(UUID.randomUUID().toString(), ", \"tags\": {\"a\": \"1\", \"b\": \"2\"}");
        String first = startedRunId(applicationId, body);

        assertEquals(first, startedRunId(applicationId, body));
    }

    @Test
    void reusedClientTokenWithAnotherRequestIsAConflict() {
        String applicationId = createApplication("");
        String token = UUID.randomUUID().toString();
        startedRunId(applicationId, sparkRun(token, ", \"name\": \"first\""));

        startJobRun(applicationId, sparkRun(token, ", \"name\": \"second\""))
                .then().statusCode(409)
                .body("message", equalTo("Request did not match the original"))
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void newClientTokenStartsAnotherRun() {
        String applicationId = createApplication("");
        String first = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        assertNotEquals(first, startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), "")));
    }

    @Test
    void missingRequiredMembersAreAValidationException() {
        String applicationId = createApplication("");

        startJobRun(applicationId, "{\"jobDriver\": {\"sparkSubmit\": {}}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("3 validation errors detected: "
                        + "Value null at 'clientToken' failed to satisfy constraint: Member must not be null; "
                        + "Value null at 'executionRoleArn' failed to satisfy constraint: Member must not be null; "
                        + "Value null at 'jobDriver.sparkSubmit.entryPoint' failed to satisfy constraint: "
                        + "Member must not be null"));
    }

    @Test
    void executionRoleOutsideTheRolePatternIsAValidationException() {
        String applicationId = createApplication("");

        startJobRun(applicationId, """
                {"clientToken": "t-1", "executionRoleArn": "arn:aws:iam::000000000000:user/someone"}
                """)
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'executionRoleArn' failed to "
                        + "satisfy constraint: Member must satisfy regular expression pattern: "
                        + "arn:(aws[a-zA-Z0-9-]*):iam::([0-9]{12}):(role((" + backslashU("002F") + ")|("
                        + backslashU("002F") + "[" + backslashU("0021") + "-" + backslashU("007F") + "]+"
                        + backslashU("002F") + "))[" + (char) 92 + "w+=,.@-]+)"));
    }

    // The pattern is quoted with its escapes as text, so the expected message spells them out.
    private static String backslashU(String hex) {
        return (char) 92 + "u" + hex;
    }

    @Test
    void unknownModeIsAValidationException() {
        String applicationId = createApplication("");

        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ", \"mode\": \"INTERACTIVE\""))
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value 'INTERACTIVE' at 'mode' failed to "
                        + "satisfy constraint: Member must satisfy enum value set: [BATCH, STREAMING]"));
    }

    @Test
    void executionTimeoutOutsideItsRangeIsAValidationException() {
        String applicationId = createApplication("");

        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ", \"executionTimeoutMinutes\": -1"))
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '-1' at 'executionTimeoutMinutes' "
                        + "failed to satisfy constraint: Member must have value greater than or equal to 0"));
        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ", \"executionTimeoutMinutes\": 1000001"))
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '1000001' at 'executionTimeoutMinutes' "
                        + "failed to satisfy constraint: Member must have value less than or equal to 1000000"));
    }

    @Test
    void aRunNeedsExactlyOneJobDriver() {
        String applicationId = createApplication("");
        String missing = """
                {"clientToken": "%s", "executionRoleArn": "%s"}
                """.formatted(UUID.randomUUID(), ROLE);
        String both = """
                {"clientToken": "%s", "executionRoleArn": "%s",
                 "jobDriver": {"sparkSubmit": {"entryPoint": "s3://bucket/jobs/app.jar"}, "hive": {"query": "SELECT 1"}}}
                """.formatted(UUID.randomUUID(), ROLE);
        String neither = """
                {"clientToken": "%s", "executionRoleArn": "%s", "jobDriver": {}}
                """.formatted(UUID.randomUUID(), ROLE);

        for (String body : List.of(missing, both, neither)) {
            startJobRun(applicationId, body)
                    .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("jobDriver must contain exactly one union member"));
        }
        givenReq().when().get("/applications/" + applicationId)
                .then().body("application.state", equalTo("CREATED"));
    }

    @Test
    void refusedTagsLeaveTheApplicationAsItWas() {
        String applicationId = createApplication("");

        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ", \"tags\": {\"aws:team\": \"data\"}"))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        givenReq().when().get("/applications/" + applicationId)
                .then().body("application.state", equalTo("CREATED"));
        givenReq().when().get("/applications/" + applicationId + "/jobruns")
                .then().body("jobRuns", empty());
    }

    @Test
    void runOnAnUnknownApplicationIsResourceNotFound() {
        startJobRun("00missing0000000", sparkRun(UUID.randomUUID().toString(), ""))
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void unknownJobRunIsResourceNotFound() {
        String applicationId = createApplication("");

        givenReq().when().get("/applications/" + applicationId + "/jobruns/00missing0000000")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        givenReq().when().delete("/applications/" + applicationId + "/jobruns/00missing0000000")
                .then().statusCode(404);
    }

    @Test
    void jobRunOfAnotherApplicationIsResourceNotFound() {
        String owner = createApplication("");
        String other = createApplication("");
        String jobRunId = startedRunId(owner, sparkRun(UUID.randomUUID().toString(), ""));

        givenReq().when().get("/applications/" + other + "/jobruns/" + jobRunId)
                .then().statusCode(404);
    }

    @Test
    void disabledAutoStartRefusesARunUntilTheApplicationStarts() {
        String applicationId = createApplication(", \"autoStartConfiguration\": {\"enabled\": false}");

        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ""))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        givenReq().when().get("/applications/" + applicationId)
                .then().body("application.state", equalTo("CREATED"));

        givenReq().when().post("/applications/" + applicationId + "/start").then().statusCode(200);
        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), "")).then().statusCode(200);
    }

    @Test
    void runOnATerminatedApplicationIsRefused() {
        String applicationId = createApplication("");
        givenReq().when().delete("/applications/" + applicationId).then().statusCode(200);

        startJobRun(applicationId, sparkRun(UUID.randomUUID().toString(), ""))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void cancellingAFinishedRunIsAValidationException() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        givenReq().when().delete("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Job run is not in a cancellable state"));
    }

    @Test
    void streamingRunKeepsRunningUntilCancelled() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId,
                sparkRun(UUID.randomUUID().toString(), ", \"mode\": \"STREAMING\""));
        String path = "/applications/" + applicationId + "/jobruns/" + jobRunId;

        givenReq().when().get(path).then().statusCode(200)
                .body("jobRun.state", equalTo("RUNNING"))
                .body("jobRun", not(hasKey("endedAt")))
                .body("jobRun", not(hasKey("totalExecutionDurationSeconds")));

        givenReq().when().delete(path).then().statusCode(200)
                .body("applicationId", equalTo(applicationId))
                .body("jobRunId", equalTo(jobRunId));

        givenReq().when().get(path).then().statusCode(200)
                .body("jobRun.state", equalTo("CANCELLED"))
                .body("jobRun.endedAt", instanceOf(Number.class));
    }

    @Test
    void listJobRunsReturnsTheNewestFirst() {
        String applicationId = createApplication("");
        String older = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));
        clock.advance(Duration.ofSeconds(5));
        String newer = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        givenReq().when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200)
                .body("jobRuns.id", contains(newer, older))
                .body("jobRuns[0].applicationId", equalTo(applicationId))
                .body("jobRuns[0].state", equalTo("SUCCESS"))
                .body("jobRuns[0].type", equalTo("SPARK"))
                .body("jobRuns[0].releaseLabel", equalTo("emr-7.5.0"))
                .body("jobRuns[0].createdBy", equalTo("arn:aws:iam::000000000000:root"))
                .body("jobRuns[0].attempt", equalTo(1))
                .body("jobRuns[0].attemptCreatedAt", instanceOf(Number.class))
                .body("jobRuns[0].attemptUpdatedAt", instanceOf(Number.class))
                .body("jobRuns[0]", not(hasKey("jobRunId")))
                .body("jobRuns[0]", not(hasKey("jobDriver")));
    }

    @Test
    void listJobRunsPagesWithNextToken() {
        String applicationId = createApplication("");
        String older = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));
        clock.advance(Duration.ofSeconds(5));
        String newer = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        String nextToken = givenReq().queryParam("maxResults", 1)
                .when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200)
                .body("jobRuns.id", contains(newer))
                .body("nextToken", notNullValue())
                .extract().path("nextToken");

        givenReq().queryParam("maxResults", 1).queryParam("nextToken", nextToken)
                .when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200)
                .body("jobRuns.id", contains(older))
                .body("$", not(hasKey("nextToken")));
    }

    @Test
    void listJobRunsRefusesAPageLargerThanFifty() {
        String applicationId = createApplication("");

        givenReq().queryParam("maxResults", 51)
                .when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '51' at 'maxResults' failed to "
                        + "satisfy constraint: Member must have value less than or equal to 50"));
    }

    @Test
    void listJobRunsRefusesATokenFromAnotherListing() {
        String applicationId = createApplication("");
        String other = createApplication("");
        startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));
        clock.advance(Duration.ofSeconds(5));
        startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));
        String nextToken = givenReq().queryParam("maxResults", 1)
                .when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200)
                .extract().path("nextToken");

        givenReq().queryParam("nextToken", nextToken)
                .when().get("/applications/" + other + "/jobruns")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        givenReq().queryParam("nextToken", nextToken).queryParam("states", "SUCCESS")
                .when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listJobRunsFiltersOnStateModeAndCreationTime() {
        String applicationId = createApplication("");
        String batch = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));
        clock.advance(Duration.ofSeconds(10));
        Instant between = clock.instant();
        clock.advance(Duration.ofSeconds(10));
        String streaming = startedRunId(applicationId,
                sparkRun(UUID.randomUUID().toString(), ", \"mode\": \"STREAMING\""));
        String base = "/applications/" + applicationId + "/jobruns";

        givenReq().queryParam("states", "RUNNING").when().get(base)
                .then().statusCode(200).body("jobRuns.id", contains(streaming));
        givenReq().queryParam("mode", "STREAMING").when().get(base)
                .then().statusCode(200).body("jobRuns.id", contains(streaming));
        givenReq().queryParam("mode", "BATCH").when().get(base)
                .then().statusCode(200).body("jobRuns.id", contains(batch));
        givenReq().queryParam("createdAtBefore", between.toString()).when().get(base)
                .then().statusCode(200).body("jobRuns.id", contains(batch));
        givenReq().queryParam("createdAtAfter", between.toString()).when().get(base)
                .then().statusCode(200).body("jobRuns.id", contains(streaming));
    }

    @Test
    void listJobRunsGroupsSeveralStatesInTheFilterOrder() {
        String applicationId = createApplication("");
        String streaming = startedRunId(applicationId,
                sparkRun(UUID.randomUUID().toString(), ", \"mode\": \"STREAMING\""));
        clock.advance(Duration.ofSeconds(5));
        String batch = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        givenReq().queryParam("states", List.of("RUNNING", "SUCCESS"))
                .when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200)
                .body("jobRuns.id", contains(streaming, batch));
    }

    @Test
    void listJobRunsRefusesStatesAndModesThatDoNotExist() {
        String base = "/applications/" + createApplication("") + "/jobruns";

        givenReq().queryParam("states", List.of("RUNNING", "SUCCEEDED")).queryParam("mode", "batch")
                .when().get(base)
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("2 validation errors detected: "
                        + "Value '[RUNNING, SUCCEEDED]' at 'states' failed to satisfy constraint: Member must "
                        + "satisfy constraint: [Member must satisfy enum value set: [SUBMITTED, PENDING, SCHEDULED, "
                        + "RUNNING, SUCCESS, FAILED, CANCELLING, CANCELLED, QUEUED]]; "
                        + "Value 'batch' at 'mode' failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[BATCH, STREAMING]"));
        givenReq().queryParam("states", List.of("SUBMITTED", "PENDING", "SCHEDULED", "RUNNING", "SUCCESS", "FAILED",
                        "CANCELLING", "CANCELLED", "QUEUED"))
                .when().get(base)
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '[SUBMITTED, PENDING, SCHEDULED, "
                        + "RUNNING, SUCCESS, FAILED, CANCELLING, CANCELLED, QUEUED]' at 'states' failed to satisfy "
                        + "constraint: Member must have length less than or equal to 8"));
    }

    @Test
    void listJobRunsOfAnUnknownApplicationIsEmpty() {
        givenReq().when().get("/applications/00missing0000000/jobruns")
                .then().statusCode(200)
                .body("jobRuns", empty());
    }

    @Test
    void fromRelease711ABatchRunsTimeoutIsOneToTenThousandEightyMinutes() {
        String current = createApplicationOn("emr-7.11.0", "");
        for (String timeout : List.of(", \"executionTimeoutMinutes\": 0",
                ", \"executionTimeoutMinutes\": 10081", ", \"mode\": \"BATCH\", \"executionTimeoutMinutes\": 0")) {
            startJobRun(current, sparkRun(UUID.randomUUID().toString(), timeout))
                    .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("executionTimeoutMinutes must be between 1 and 10080 for a BATCH job "
                            + "run on release emr-7.11.0 or later."));
        }
        givenReq().when().get("/applications/" + current)
                .then().body("application.state", equalTo("CREATED"));

        startJobRun(current, sparkRun(UUID.randomUUID().toString(), ", \"executionTimeoutMinutes\": 10080"))
                .then().statusCode(200);
        startJobRun(current, sparkRun(UUID.randomUUID().toString(),
                ", \"mode\": \"STREAMING\", \"executionTimeoutMinutes\": 0"))
                .then().statusCode(200);
        String earlier = createApplicationOn("emr-7.10.0", "");
        for (String timeout : List.of(", \"executionTimeoutMinutes\": 0", ", \"executionTimeoutMinutes\": 1000000")) {
            startJobRun(earlier, sparkRun(UUID.randomUUID().toString(), timeout)).then().statusCode(200);
        }
    }

    @Test
    void aRunIsItsOneAttempt() {
        String applicationId = createApplication("");
        String path = "/applications/" + applicationId + "/jobruns/"
                + startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));
        JsonPath run = givenReq().config(DECIMAL_NUMBERS).when().get(path)
                .then().statusCode(200).extract().jsonPath();

        assertEquals(1, run.getInt("jobRun.attempt"));
        assertEquals(run.<BigDecimal>get("jobRun.createdAt"), run.<BigDecimal>get("jobRun.attemptCreatedAt"));
        assertEquals(run.<BigDecimal>get("jobRun.updatedAt"), run.<BigDecimal>get("jobRun.attemptUpdatedAt"));

        givenReq().queryParam("attempt", 1).when().get(path)
                .then().statusCode(200).body("jobRun.attempt", equalTo(1));
        givenReq().queryParam("attempt", 2).when().get(path)
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        givenReq().queryParam("attempt", 0).when().get(path)
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '0' at 'attempt' failed to satisfy "
                        + "constraint: Member must have value greater than or equal to 1"));
        givenReq().queryParam("attempt", "first").when().get(path)
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        // An empty value never reaches the parameter: RESTEasy Reactive binds it as null, so it reads as
        // no attempt, the latest one, as Pagination.parseMaxResults reads an empty maxResults.
        givenReq().queryParam("attempt", "").when().get(path)
                .then().statusCode(200).body("jobRun.attempt", equalTo(1));
    }

    @Test
    void listJobRunAttemptsReportsTheRunsOneAttempt() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId,
                sparkRun(UUID.randomUUID().toString(), ", \"name\": \"nightly\", \"mode\": \"BATCH\""));
        String path = "/applications/" + applicationId + "/jobruns/" + jobRunId;
        JsonPath run = givenReq().config(DECIMAL_NUMBERS).when().get(path)
                .then().statusCode(200).extract().jsonPath();

        JsonPath attempts = givenReq().config(DECIMAL_NUMBERS).when().get(path + "/attempts")
                .then().statusCode(200)
                .body("jobRunAttempts", hasSize(1))
                .body("$", not(hasKey("nextToken")))
                .extract().jsonPath();
        assertEquals(applicationId, attempts.getString("jobRunAttempts[0].applicationId"));
        assertEquals(jobRunId, attempts.getString("jobRunAttempts[0].id"));
        assertEquals("nightly", attempts.getString("jobRunAttempts[0].name"));
        assertEquals("BATCH", attempts.getString("jobRunAttempts[0].mode"));
        assertEquals(run.getString("jobRun.arn"), attempts.getString("jobRunAttempts[0].arn"));
        assertEquals("arn:aws:iam::000000000000:root", attempts.getString("jobRunAttempts[0].createdBy"));
        assertEquals(run.<BigDecimal>get("jobRun.createdAt"),
                attempts.<BigDecimal>get("jobRunAttempts[0].jobCreatedAt"));
        assertEquals(run.<BigDecimal>get("jobRun.createdAt"), attempts.<BigDecimal>get("jobRunAttempts[0].createdAt"));
        assertEquals(run.<BigDecimal>get("jobRun.updatedAt"), attempts.<BigDecimal>get("jobRunAttempts[0].updatedAt"));
        assertEquals(ROLE, attempts.getString("jobRunAttempts[0].executionRole"));
        assertEquals("SUCCESS", attempts.getString("jobRunAttempts[0].state"));
        assertEquals("emr-7.5.0", attempts.getString("jobRunAttempts[0].releaseLabel"));
        assertEquals("SPARK", attempts.getString("jobRunAttempts[0].type"));
        assertEquals(1, attempts.getInt("jobRunAttempts[0].attempt"));
    }

    @Test
    void listJobRunAttemptsRefusesAPageLargerThanFiftyAndAnUnknownRun() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        givenReq().queryParam("maxResults", 51)
                .when().get("/applications/" + applicationId + "/jobruns/" + jobRunId + "/attempts")
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '51' at 'maxResults' failed to "
                        + "satisfy constraint: Member must have value less than or equal to 50"));
        givenReq().when().get("/applications/" + applicationId + "/jobruns/00missing0000000/attempts")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        givenReq().when().get("/applications/00missing0000000/jobruns/" + jobRunId + "/attempts")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        givenReq().queryParam("nextToken", "bm90LWEtdG9rZW4")
                .when().get("/applications/" + applicationId + "/jobruns/" + jobRunId + "/attempts")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void jobRunsStayInTheirRegion() {
        String applicationId = createApplication("");
        String jobRunId = startedRunId(applicationId, sparkRun(UUID.randomUUID().toString(), ""));

        EmrServerlessRequests.emrServerless("eu-west-1").when().get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                .then().statusCode(404);
        EmrServerlessRequests.emrServerless("eu-west-1").when().get("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200).body("jobRuns", empty());
    }
}
