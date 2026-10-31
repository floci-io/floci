package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CodeDeployListApplicationsPaginationIntegrationTest {

    private static final String ACCOUNT = "741666200001";
    private static final String REGION = "ap-south-2";
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        for (String name : created) {
            call("DeleteApplication", REGION, ACCOUNT, "{\"applicationName\":\"" + name + "\"}", 200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"not-a-valid-token\"", "\"%%%%\"", "\"YW5vdGhlci1zY29wZTph\"", "1", "true", "{}", "[]"})
    void malformedTokensFailEvenWhenNoApplicationsExist(String value) {
        Map<String, Object> error = list(REGION, ACCOUNT, "{\"nextToken\":" + value + "}", 400);
        assertEquals("InvalidNextTokenException", error.get("__type"));
    }

    @Test
    void continuationWalksTheSortedListWithoutDuplicates() {
        List<String> expected = createApplications(101);
        Map<String, Object> first = list(REGION, ACCOUNT, "{}", 200);
        assertEquals(expected.subList(0, 100), first.get("applications"));
        String token = (String) first.get("nextToken");
        assertNotNull(token);
        Map<String, Object> last = list(REGION, ACCOUNT, tokenRequest(token), 200);
        assertEquals(expected.subList(100, 101), last.get("applications"));
        assertFalse(last.containsKey("nextToken"));
    }

    @Test
    void exactlyOneFullPageOmitsTheToken() {
        List<String> expected = createApplications(100);
        Map<String, Object> response = list(REGION, ACCOUNT, "{}", 200);
        assertEquals(expected, response.get("applications"));
        assertFalse(response.containsKey("nextToken"));
    }

    @Test
    void emptyCollectionsAcceptOmittedNullAndEmptyToken() {
        for (String body : List.of("{}", "{\"nextToken\":null}", "{\"nextToken\":\"\"}")) {
            Map<String, Object> response = list(REGION, ACCOUNT, body, 200);
            assertEquals(List.of(), response.get("applications"));
            assertFalse(response.containsKey("nextToken"));
        }
    }

    @Test
    void tokensCannotCrossAccountOrRegion() {
        createApplications(101);
        String token = (String) list(REGION, ACCOUNT, "{}", 200).get("nextToken");
        assertNotNull(token);
        for (Map<String, Object> error : List.of(
                list(REGION, "741666200002", tokenRequest(token), 400),
                list("ap-southeast-3", ACCOUNT, tokenRequest(token), 400))) {
            assertEquals("InvalidNextTokenException", error.get("__type"));
        }
    }

    @Test
    void anIssuedTokenStillResumesAfterItsCursorApplicationIsDeleted() {
        List<String> expected = createApplications(101);
        String token = (String) list(REGION, ACCOUNT, "{}", 200).get("nextToken");
        assertNotNull(token);
        String cursor = expected.get(99);
        call("DeleteApplication", REGION, ACCOUNT, "{\"applicationName\":\"" + cursor + "\"}", 200);
        created.remove(cursor);
        Map<String, Object> last = list(REGION, ACCOUNT, tokenRequest(token), 200);
        assertEquals(expected.subList(100, 101), last.get("applications"));
        assertFalse(last.containsKey("nextToken"));
    }

    private List<String> createApplications(int count) {
        for (int i = count - 1; i >= 0; i--) {
            String name = "token-test-" + String.format("%03d", i);
            call("CreateApplication", REGION, ACCOUNT, "{\"applicationName\":\"" + name + "\"}", 200);
            created.add(name);
        }
        return created.stream().sorted().toList();
    }

    private Map<String, Object> list(String region, String account, String body, int status) {
        return call("ListApplications", region, account, body, status);
    }

    private Map<String, Object> call(String action, String region, String account, String body, int status) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "CodeDeploy_20141006." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + region + "/codedeploy/aws4_request")
                .body(body).when().post("/").then().statusCode(status).extract().jsonPath().getMap("");
    }

    private String tokenRequest(String token) {
        return "{\"nextToken\":\"" + token + "\"}";
    }
}
