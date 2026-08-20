package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BedrockAgentCorePaginationIntegrationTest {

    private static String body(String name) {
        return "{\"agentRuntimeName\":\"" + name + "\","
                + "\"agentRuntimeArtifact\":{\"containerConfiguration\":{\"containerUri\":\"x:latest\"}},"
                + "\"networkConfiguration\":{\"networkMode\":\"PUBLIC\"},"
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/agent\"}";
    }

    @Test
    void listRuntimesPaginatesWithoutDuplicatesOrGaps() {
        // Exercises the HTTP-level nextToken round-trip (controller <-> service cursor).
        Set<String> mine = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String name = "pageAgent" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String id = given().contentType("application/json").body(body(name))
                    .when().put("/runtimes/")
                    .then().statusCode(202)
                    .extract().path("agentRuntimeId");
            mine.add(id);
        }

        List<String> collected = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            var req = given().contentType("application/json").queryParam("maxResults", 2);
            if (token != null) {
                req = req.queryParam("nextToken", token);
            }
            ExtractableResponse<Response> resp = req.when().post("/runtimes/")
                    .then().statusCode(200).extract();
            List<String> ids = resp.path("agentRuntimes.agentRuntimeId");
            if (ids != null) {
                collected.addAll(ids);
                assertTrue(ids.size() <= 2, "page exceeded maxResults");
            }
            token = resp.path("nextToken");
            assertTrue(++pages < 200, "pagination did not terminate");
        } while (token != null);

        // No duplicates across pages, and every runtime we created appears exactly once.
        assertEquals(collected.size(), new HashSet<>(collected).size(), "duplicate items across pages");
        assertTrue(collected.containsAll(mine), "a created runtime was dropped by pagination");
    }

    // AWS declares MaxResults with a minimum of 1; 0 is real out-of-range input, not a
    // synonym for "omitted" (an absent query param instead).
    @Test
    void listRuntimesRejectsZeroMaxResults() {
        given().contentType("application/json").queryParam("maxResults", 0)
                .when().post("/runtimes/")
                .then().statusCode(400);
    }
}
