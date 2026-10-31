package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodeBuildBuildListPaginationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String PROJECT = "pagination-builds";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeBuildService service = new CodeBuildService(
            mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null);
    private final CodeBuildJsonHandler handler = new CodeBuildJsonHandler(service, mapper);

    @BeforeEach
    void createProject() {
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        service.createProject(REGION, ACCOUNT, PROJECT, null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);
    }

    @Test
    void listBuildsReturnsOneHundredIdsAndContinuation() throws Exception {
        createBuilds(101);
        JsonNode first = list("ListBuilds", Map.of());
        assertEquals(100, ids(first).size());
        assertTrue(first.path("nextToken").isTextual());

        JsonNode last = list("ListBuilds", Map.of("nextToken", first.path("nextToken").asText()));
        assertEquals(1, ids(last).size());
        assertFalse(last.has("nextToken"));
        List<String> seen = new ArrayList<>(ids(first));
        seen.addAll(ids(last));
        assertEquals(IntStream.rangeClosed(1, 101).mapToObj(number -> PROJECT + ":" + number)
                .sorted(Comparator.reverseOrder()).toList(), seen);
    }

    @Test
    void listBuildsForProjectReturnsOneHundredIdsAndContinuation() throws Exception {
        createBuilds(101);
        JsonNode first = list("ListBuildsForProject", Map.of("projectName", PROJECT));
        assertEquals(100, ids(first).size());
        assertTrue(first.path("nextToken").isTextual());

        JsonNode last = list("ListBuildsForProject", Map.of(
                "projectName", PROJECT, "nextToken", first.path("nextToken").asText()));
        assertEquals(List.of(PROJECT + ":1"), ids(last));
        assertFalse(last.has("nextToken"));
        List<String> seen = new ArrayList<>(ids(first));
        seen.addAll(ids(last));
        assertEquals(IntStream.rangeClosed(1, 101).mapToObj(number -> PROJECT + ":" + number)
                .toList().reversed(), seen);
    }

    @Test
    void exactPageOmitsContinuationAndAllowsProjectSortOrder() throws Exception {
        createBuilds(100);
        JsonNode allBuilds = list("ListBuilds", Map.of());
        assertEquals(100, ids(allBuilds).size());
        assertFalse(allBuilds.has("nextToken"));

        JsonNode projectBuilds = list("ListBuildsForProject",
                Map.of("projectName", PROJECT, "sortOrder", "ASCENDING"));
        assertEquals(IntStream.rangeClosed(1, 100).mapToObj(number -> PROJECT + ":" + number).toList(),
                ids(projectBuilds));
        assertFalse(projectBuilds.has("nextToken"));
    }

    @Test
    void listBuildsContinuesInAscendingBuildIdOrder() throws Exception {
        createBuilds(101);
        JsonNode first = list("ListBuilds", Map.of("sortOrder", "ASCENDING"));
        JsonNode last = list("ListBuilds", Map.of(
                "sortOrder", "ASCENDING", "nextToken", first.path("nextToken").asText()));
        List<String> seen = new ArrayList<>(ids(first));
        seen.addAll(ids(last));
        assertEquals(IntStream.rangeClosed(1, 101).mapToObj(number -> PROJECT + ":" + number)
                .sorted().toList(), seen);
        assertFalse(last.has("nextToken"));
    }

    @Test
    void projectBuildsContinueThroughThreePages() throws Exception {
        createBuilds(201);
        List<String> seen = new ArrayList<>();
        String token = null;
        for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
            Map<String, String> request = token == null
                    ? Map.of("projectName", PROJECT)
                    : Map.of("projectName", PROJECT, "nextToken", token);
            JsonNode page = list("ListBuildsForProject", request);
            assertEquals(pageIndex < 2 ? 100 : 1, ids(page).size());
            seen.addAll(ids(page));
            token = page.path("nextToken").isTextual() ? page.path("nextToken").asText() : null;
        }
        assertEquals(IntStream.rangeClosed(1, 201).mapToObj(number -> PROJECT + ":" + number)
                .toList().reversed(), seen);
    }

    @Test
    void projectContinuationKeepsItsPositionWhenANewBuildStarts() throws Exception {
        createBuilds(101);
        JsonNode first = list("ListBuildsForProject", Map.of("projectName", PROJECT));
        String token = first.path("nextToken").asText();

        createBuilds(1);

        JsonNode last = list("ListBuildsForProject", Map.of("projectName", PROJECT, "nextToken", token));
        assertEquals(List.of(PROJECT + ":1"), ids(last));
        assertFalse(last.has("nextToken"));
    }

    @Test
    void projectSortOrderIsRejectedAboveOneHundredBuilds() {
        createBuilds(101);
        AwsException error = assertThrows(AwsException.class, () -> list("ListBuildsForProject",
                Map.of("projectName", PROJECT, "sortOrder", "ASCENDING")));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void projectNameIsRequiredForProjectBuildListing() {
        AwsException error = assertThrows(AwsException.class,
                () -> list("ListBuildsForProject", Map.of()));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void continuationRejectsOtherRequestScopesAndMalformedTokens() throws Exception {
        createBuilds(101);
        String globalToken = list("ListBuilds", Map.of()).path("nextToken").asText();
        String projectToken = list("ListBuildsForProject", Map.of("projectName", PROJECT))
                .path("nextToken").asText();
        assertInvalidToken("ListBuilds", Map.of("nextToken", globalToken, "sortOrder", "ASCENDING"),
                REGION, ACCOUNT);
        assertInvalidToken("ListBuilds", Map.of("nextToken", globalToken), "eu-west-1", ACCOUNT);
        assertInvalidToken("ListBuilds", Map.of("nextToken", globalToken), REGION, "111111111111");
        assertInvalidToken("ListBuilds", Map.of("nextToken", "not-a-token!"), REGION, ACCOUNT);
        assertInvalidToken("ListBuilds", Map.of("nextToken", 42), REGION, ACCOUNT);
        assertInvalidToken("ListBuildsForProject", Map.of("projectName", PROJECT, "nextToken", globalToken),
                REGION, ACCOUNT);
        assertInvalidToken("ListBuildsForProject", Map.of("projectName", "another", "nextToken", projectToken),
                REGION, ACCOUNT);
    }

    private JsonNode list(String action, Map<String, ?> request) throws Exception {
        return list(action, request, REGION, ACCOUNT);
    }

    private JsonNode list(String action, Map<String, ?> request, String region, String account) throws Exception {
        try (Response response = handler.handle(action, mapper.valueToTree(request), region, account)) {
            return mapper.valueToTree(response.getEntity());
        }
    }

    private void assertInvalidToken(String action, Map<String, ?> request, String region, String account) {
        AwsException error = assertThrows(AwsException.class, () -> list(action, request, region, account));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private void createBuilds(int count) {
        for (int number = 0; number < count; number++) {
            service.startBuild(REGION, ACCOUNT, PROJECT, null, null, null, null, null, null, null);
        }
    }

    private List<String> ids(JsonNode page) {
        return mapper.convertValue(page.path("ids"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
