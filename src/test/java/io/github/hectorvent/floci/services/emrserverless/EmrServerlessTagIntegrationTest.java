package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.EmrServerlessRequests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.EmrServerlessRequests.tagsPath;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EmrServerlessTagIntegrationTest {

    @Inject
    EmrServerlessService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification emr() {
        return EmrServerlessRequests.emrServerless("us-east-1").urlEncodingEnabled(false);
    }

    /** A tags map of {@code count} distinct keys, as JSON. */
    private static String tagMap(int count) {
        StringBuilder tags = new StringBuilder("{");
        for (int i = 0; i < count; i++) {
            tags.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": \"v\"");
        }
        return tags.append("}").toString();
    }

    private static String[] startJobRun(String applicationId, String tagsJson) {
        JsonPath started = emr()
                .body("""
                        { "clientToken": "%s", "executionRoleArn": "arn:aws:iam::000000000000:role/emr-serverless-job",
                          "jobDriver": { "sparkSubmit": { "entryPoint": "s3://bucket/jobs/app.jar" } }, "tags": %s }
                        """.formatted(UUID.randomUUID(), tagsJson))
        .when().post("/applications/" + applicationId + "/jobruns")
        .then().statusCode(200)
                .extract().jsonPath();
        return new String[] {started.getString("jobRunId"), started.getString("arn")};
    }

    private static String[] createApplication(String tagsJson) {
        JsonPath created = emr()
                .body("""
                        { "name": "tags-%s", "releaseLabel": "emr-7.5.0", "type": "SPARK",
                          "clientToken": "%s", "tags": %s }
                        """.formatted(UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), tagsJson))
        .when().post("/applications")
        .then().statusCode(200)
                .extract().jsonPath();
        return new String[] {created.getString("applicationId"), created.getString("arn")};
    }

    @Test
    void tagsRoundTripOnTheApplicationArnAndShowInGetApplication() {
        String[] app = createApplication("{ \"team\": \"data\" }");
        String arn = app[1];

        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags.team", equalTo("data"));

        emr().body("{ \"tags\": { \"env\": \"dev\", \"team\": \"platform\" } }")
        .when().post(tagsPath(arn))
        .then().statusCode(200);

        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags", aMapWithSize(2))
                .body("tags.team", equalTo("platform"))
                .body("tags.env", equalTo("dev"));
        emr().when().get("/applications/" + app[0])
        .then().statusCode(200)
                .body("application.tags.env", equalTo("dev"));

        emr().when().delete(tagsPath(arn) + "?tagKeys=env")
        .then().statusCode(200);

        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags", not(hasKey("env")))
                .body("tags.team", equalTo("platform"));
    }

    @Test
    void terminatedApplicationsCannotBeTagged() {
        String[] app = createApplication("{ \"team\": \"data\" }");
        String path = tagsPath(app[1]);

        emr().when().delete("/applications/" + app[0])
        .then().statusCode(200);

        emr().when().get(path)
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        emr().body("{ \"tags\": { \"env\": \"dev\" } }")
        .when().post(path)
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        emr().when().delete(path + "?tagKeys=team")
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        emr().when().get("/applications/" + app[0])
        .then().statusCode(200)
                .body("application.state", equalTo("TERMINATED"))
                .body("application.tags.team", equalTo("data"))
                .body("application.tags", not(hasKey("env")));
    }

    @Test
    void unknownApplicationsAndJobRunsAreNotFoundAndBadTagsAreRefused() {
        String arn = createApplication("{}")[1];
        String missing = arn.substring(0, arn.lastIndexOf('/') + 1) + "00missing00";

        emr().when().get(tagsPath(missing))
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        emr().when().get(tagsPath(arn + "/jobruns/00abc00"))
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        emr().body("{ \"tags\": { \"bad#key\": \"x\" } }")
        .when().post(tagsPath(arn))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        emr().body("{ \"tags\": " + tagMap(51) + " }")
        .when().post(tagsPath(arn))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void nonStringValuesAndBadUntagKeysAreRefused() {
        String arn = createApplication("{}")[1];

        emr().body("{ \"tags\": { \"team\": 123 } }")
        .when().post(tagsPath(arn))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr().when().delete(tagsPath(arn) + "?tagKeys=")
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr().when().delete(tagsPath(arn) + "?tagKeys=" + URLEncoder.encode("bad#key", StandardCharsets.UTF_8))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags", aMapWithSize(0));
    }

    @Test
    void createApplicationAppliesTheSameTagRules() {
        for (String tags : List.of(tagMap(51), "{ \"bad#key\": \"v\" }")) {
            emr().body("""
                            { "name": "limits", "releaseLabel": "emr-7.5.0", "type": "SPARK",
                              "clientToken": "%s", "tags": %s }
                            """.formatted(UUID.randomUUID(), tags))
            .when().post("/applications")
            .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
    }

    /**
     * The user guide's tagging limitations: at most 50 user-created tags per resource, and "aws:" in any
     * case is reserved as the prefix of a key or a value. An empty tags map is valid and changes nothing.
     */
    @Test
    void theUserGuideLimitsApplyAndAnEmptyMapIsANoOp() {
        String arn = createApplication("{ \"team\": \"data\" }")[1];

        emr().body("{ \"tags\": " + tagMap(49) + " }").when().post(tagsPath(arn)).then().statusCode(200);
        emr().when().get(tagsPath(arn)).then().statusCode(200).body("tags", aMapWithSize(50));
        emr().body("{ \"tags\": { \"one-more\": \"v\" } }")
        .when().post(tagsPath(arn))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        for (String reserved : List.of("{ \"aws:owner\": \"x\" }", "{ \"AWS:owner\": \"x\" }",
                "{ \"Aws:owner\": \"x\" }", "{ \"owner\": \"aWs:x\" }")) {
            emr().body("{ \"tags\": " + reserved + " }")
            .when().post(tagsPath(arn))
            .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }

        emr().body("{ \"tags\": {} }").when().post(tagsPath(arn)).then().statusCode(200);
        emr().when().get(tagsPath(arn)).then().statusCode(200).body("tags", aMapWithSize(50));
    }

    /** Tag writes read, change and store the whole application; concurrent ones must not lose each other. */
    @Test
    void concurrentTagWritesAreAllKept() throws Exception {
        EmrServerlessService.ResourceArn resource = service.resourceArn(createApplication("{}")[1]);
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "k" + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    service.tagResource(resource, Map.of(key, "v"));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads, service.listTags(resource).size());
    }

    @Test
    void anEmptyTagMapChangesNothingButMissingTagsOrKeysAreRefused() {
        EmrServerlessService.ResourceArn resource = service.resourceArn(createApplication("{ \"team\": \"data\" }")[1]);

        service.tagResource(resource, Map.of());
        assertEquals(Map.of("team", "data"), service.listTags(resource));
        assertEquals("ValidationException",
                assertThrows(AwsException.class,
                        () -> service.tagResource(resource, null)).getErrorCode());
        assertEquals("ValidationException",
                assertThrows(AwsException.class,
                        () -> service.untagResource(resource, List.of())).getErrorCode());
    }

    @Test
    void tagsRoundTripOnAJobRunArnAndShowInGetJobRun() {
        String[] app = createApplication("{ \"team\": \"app\" }");
        String[] run = startJobRun(app[0], "{ \"team\": \"data\" }");
        String arn = run[1];

        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags", aMapWithSize(1))
                .body("tags.team", equalTo("data"));

        emr().body("{ \"tags\": { \"env\": \"dev\", \"team\": \"platform\" } }")
        .when().post(tagsPath(arn))
        .then().statusCode(200);
        emr().when().get("/applications/" + app[0] + "/jobruns/" + run[0])
        .then().statusCode(200)
                .body("jobRun.state", equalTo("SUCCESS"))
                .body("jobRun.tags.team", equalTo("platform"))
                .body("jobRun.tags.env", equalTo("dev"));

        emr().when().delete(tagsPath(arn) + "?tagKeys=env")
        .then().statusCode(200);
        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags", not(hasKey("env")))
                .body("tags.team", equalTo("platform"));
        emr().when().get(tagsPath(app[1]))
        .then().statusCode(200)
                .body("tags", aMapWithSize(1))
                .body("tags.team", equalTo("app"));
    }

    @Test
    void aDeletedApplicationsRunsCannotBeTagged() {
        String applicationId = createApplication("{}")[0];
        String[] run = startJobRun(applicationId, "{ \"team\": \"data\" }");
        String path = tagsPath(run[1]);

        emr().when().post("/applications/" + applicationId + "/stop")
        .then().statusCode(200);
        emr().when().delete("/applications/" + applicationId)
        .then().statusCode(200);

        emr().when().get(path)
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Resource " + run[1] + " not found"));
        emr().body("{ \"tags\": { \"env\": \"dev\" } }")
        .when().post(path)
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        emr().when().delete(path + "?tagKeys=team")
        .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        emr().when().get("/applications/" + applicationId + "/jobruns/" + run[0])
        .then().statusCode(200)
                .body("jobRun.tags.team", equalTo("data"))
                .body("jobRun.tags", not(hasKey("env")));
    }

    @Test
    void aJobRunArnMustBeTheRunsOwnAndSessionArnsNameNothing() {
        String[] owner = createApplication("{}");
        String[] other = createApplication("{}");
        String[] run = startJobRun(owner[0], "{ \"team\": \"data\" }");

        for (String arn : List.of(other[1] + "/jobruns/" + run[0],
                run[1].replace(":000000000000:", ":111111111111:"),
                owner[1] + "/sessions/00abc0000000000")) {
            emr().when().get(tagsPath(arn))
            .then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            emr().body("{ \"tags\": { \"env\": \"dev\" } }")
            .when().post(tagsPath(arn))
            .then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        }
        emr().when().get(tagsPath(run[1]))
        .then().statusCode(200)
                .body("tags", aMapWithSize(1));
    }

    @Test
    void jobRunsKeepTheSameTagRules() {
        String applicationId = createApplication("{}")[0];
        String arn = startJobRun(applicationId, tagMap(50))[1];

        emr().body("{ \"tags\": { \"one-more\": \"v\" } }")
        .when().post(tagsPath(arn))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("A resource can have at most 50 tags."));
        emr().body("{ \"tags\": { \"aws:owner\": \"x\" } }")
        .when().post(tagsPath(arn))
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr().when().delete(tagsPath(arn) + "?tagKeys=")
        .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        emr().when().get(tagsPath(arn))
        .then().statusCode(200)
                .body("tags", aMapWithSize(50));
    }
}
