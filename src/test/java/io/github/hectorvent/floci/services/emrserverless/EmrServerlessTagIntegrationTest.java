package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.core.common.AwsException;
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

import static io.restassured.RestAssured.given;
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
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20260929/us-east-1/emr-serverless/aws4_request, "
                        + "SignedHeaders=host;x-amz-date, Signature=test")
                .contentType("application/json")
                .urlEncodingEnabled(false);
    }

    /** The SDK sends the ARN as one path segment with ':' and '/' percent-encoded. */
    private static String tagsPath(String arn) {
        return "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
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

        StringBuilder tooMany = new StringBuilder("{ \"tags\": {");
        for (int i = 0; i < 51; i++) {
            tooMany.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": \"v\"");
        }
        tooMany.append("} }");
        emr().body(tooMany.toString())
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
        StringBuilder tooMany = new StringBuilder("{");
        for (int i = 0; i < 51; i++) {
            tooMany.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": \"v\"");
        }
        tooMany.append("}");
        for (String tags : List.of(tooMany.toString(), "{ \"bad#key\": \"v\" }")) {
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
        StringBuilder fortyNine = new StringBuilder("{ \"tags\": {");
        for (int i = 0; i < 49; i++) {
            fortyNine.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": \"v\"");
        }
        fortyNine.append("} }");

        emr().body(fortyNine.toString()).when().post(tagsPath(arn)).then().statusCode(200);
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
        String arn = createApplication("{}")[1];
        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String key = "k" + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    service.tagResource(arn, Map.of(key, "v"));
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

        assertEquals(threads, service.listTags(arn).size());
    }

    @Test
    void anEmptyTagMapChangesNothingButMissingTagsOrKeysAreRefused() {
        String arn = createApplication("{ \"team\": \"data\" }")[1];

        service.tagResource(arn, Map.of());
        assertEquals(Map.of("team", "data"), service.listTags(arn));
        assertEquals("ValidationException",
                assertThrows(AwsException.class,
                        () -> service.tagResource(arn, null)).getErrorCode());
        assertEquals("ValidationException",
                assertThrows(AwsException.class,
                        () -> service.untagResource(arn, List.of())).getErrorCode());
    }
}
