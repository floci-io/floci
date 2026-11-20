package io.floci.aws.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class BedrockAgentCoreTagRequestValidationIntegrationTest {

    private static Map<String, String> numberedTags(int count) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            tags.put("key" + i, "value");
        }
        return tags;
    }

    @Test
    void rejectedTagRequestsLeaveTheRuntimeTagsUnchangedAndBoundaryValuesAreAccepted() {
        ExtractableResponse<Response> runtime = given().contentType("application/json").body("""
                {
                  "agentRuntimeName": "tagRequestValidation",
                  "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
                  "networkConfiguration": {"networkMode": "PUBLIC"},
                  "roleArn": "arn:aws:iam::000000000000:role/agent",
                  "tags": {"existing": "kept"}
                }
                """).when().put("/runtimes/").then().statusCode(202).extract();
        String arn = runtime.path("agentRuntimeArn");
        String runtimeId = runtime.path("agentRuntimeId");
        try {
            List<Object> requests = new ArrayList<>();
            for (Map<String, String> tags : List.of(numberedTags(51), Map.of("", "value"),
                    Map.of("k".repeat(129), "value"), Map.of("bad#key", "value"),
                    Map.of("key", "v".repeat(257)), Map.of("key", "bad#value"))) {
                requests.add(Map.of("tags", tags));
            }
            requests.addAll(List.of("{}", "{\"tags\":null}", "{\"tags\":[]}",
                    "{\"tags\":{\"key\":1}}", "{\"tags\":{\"key\":null}}", "[]"));
            for (Object request : requests) {
                RequestSpecification spec = given().contentType("application/json");
                if (request instanceof String rawJson) {
                    spec.body(rawJson);
                } else {
                    spec.body(request);
                }
                spec.when().post("/tags/" + arn).then().statusCode(400)
                        .body("__type", equalTo("ValidationException"));
                given().when().get("/tags/" + arn).then().statusCode(200)
                        .body("tags", equalTo(Map.of("existing", "kept")));
            }

            Map<String, String> boundary = numberedTags(47);
            boundary.put("existing", "changed");
            boundary.put("k".repeat(128), "v".repeat(256));
            boundary.put("empty", "");
            given().contentType("application/json").body(Map.of("tags", boundary))
                    .when().post("/tags/" + arn).then().statusCode(204);
            given().when().get("/tags/" + arn).then().statusCode(200).body("tags", equalTo(boundary));
            given().contentType("application/json").body(Map.of("tags", Map.of("key0", "overwritten")))
                    .when().post("/tags/" + arn).then().statusCode(204);
            boundary.put("key0", "overwritten");
            given().contentType("application/json").body("{\"tags\":{}}")
                    .when().post("/tags/" + arn).then().statusCode(204);
            given().when().get("/tags/" + arn).then().statusCode(200).body("tags", equalTo(boundary));
        } finally {
            given().when().delete("/runtimes/" + runtimeId).then().statusCode(202);
        }
    }
}
