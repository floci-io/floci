package io.floci.aws.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreTagFixtures.invalidTagMaps;
import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreTagFixtures.malformedTagBodies;
import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreTagFixtures.numberedTags;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class BedrockAgentCoreTagRequestValidationIntegrationTest {

    @Test
    void rejectedTagRequestsLeaveTheRuntimeTagsUnchangedAndBoundaryValuesAreAccepted() {
        String runtimeName = "tagValidation" + UUID.randomUUID().toString().replace("-", "");
        ExtractableResponse<Response> runtime = given().contentType("application/json").body("""
                {
                  "agentRuntimeName": "%s",
                  "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "x:latest"}},
                  "networkConfiguration": {"networkMode": "PUBLIC"},
                  "roleArn": "arn:aws:iam::000000000000:role/agent",
                  "tags": {"existing": "kept"}
                }
                """.formatted(runtimeName)).when().put("/runtimes/").then().statusCode(202).extract();
        String arn = runtime.path("agentRuntimeArn");
        String runtimeId = runtime.path("agentRuntimeId");
        try {
            List<Object> requests = new ArrayList<>();
            for (Map<String, String> tags : invalidTagMaps()) {
                requests.add(Map.of("tags", tags));
            }
            requests.addAll(malformedTagBodies());
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
