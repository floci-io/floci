package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodeBuildListProjectsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeBuildService service = new CodeBuildService(
            mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null);
    private final CodeBuildJsonHandler handler = new CodeBuildJsonHandler(service, mapper);

    @ParameterizedTest
    @CsvSource({
            "NAME, ASCENDING, alpha:mu:zeta",
            "NAME, DESCENDING, zeta:mu:alpha",
            "CREATED_TIME, ASCENDING, zeta:alpha:mu",
            "CREATED_TIME, DESCENDING, mu:alpha:zeta",
            "LAST_MODIFIED_TIME, ASCENDING, mu:zeta:alpha",
            "LAST_MODIFIED_TIME, DESCENDING, alpha:zeta:mu"
    })
    void sortsByEveryRequestedCriterionAndDirection(String sortBy, String sortOrder, String expected)
            throws Exception {
        createProject(REGION, "zeta", 1.0, 2.0);
        createProject(REGION, "alpha", 2.0, 3.0);
        createProject(REGION, "mu", 3.0, 1.0);
        createProject("eu-west-1", "other-region", 0.0, 0.0);

        assertEquals(List.of(expected.split(":")), list(Map.of("sortBy", sortBy, "sortOrder", sortOrder)));
    }

    @ParameterizedTest
    @CsvSource({"sortBy, UNKNOWN", "sortBy, name", "sortBy, ''", "sortOrder, UNKNOWN", "sortOrder, ascending"})
    void rejectsInvalidSortOptionsEvenWhenThereAreNoProjects(String parameter, String value) {
        AwsException error = assertThrows(AwsException.class, () -> list(Map.of(parameter, value)));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Invalid " + parameter + ": " + value, error.getMessage());
    }

    @Test
    void retainsUnspecifiedOrderAndHandlesOneSortOption() throws Exception {
        createProject(REGION, "zeta", 1.0, 2.0);
        createProject(REGION, "alpha", 2.0, 3.0);
        assertEquals(service.listProjects(REGION), list(Map.of()));
        assertEquals(List.of("alpha", "zeta"), list(Map.of("sortBy", "NAME")));
        assertEquals(List.of("alpha", "zeta"), list(Map.of("sortOrder", "ASCENDING")));
        assertEquals(List.of("zeta", "alpha"), list(Map.of("sortOrder", "DESCENDING")));
    }

    @Test
    void sortsEmptyLists() throws Exception {
        assertEquals(List.of(), list(Map.of("sortBy", "CREATED_TIME", "sortOrder", "DESCENDING")));
    }

    @ParameterizedTest
    @CsvSource({"CREATED_TIME", "LAST_MODIFIED_TIME"})
    void toleratesMissingTimestampsInStoredProjects(String sortBy) throws Exception {
        createProject(REGION, "zeta", 1.0, 1.0);
        createProject(REGION, "alpha", null, null);
        assertEquals(List.of("alpha", "zeta"), list(Map.of("sortBy", sortBy, "sortOrder", "ASCENDING")));
    }

    @Test
    void paginatesWithoutChangingExistingSortOrder() throws Exception {
        IntStream.range(0, 101).forEach(index -> createProject(REGION,
                "project-%03d".formatted(index), (double) (101 - index), (double) index));

        assertPagesMatchServiceOrder(Map.of(), service.listProjects(REGION).stream().sorted().toList());
        assertPagesMatchServiceOrder(Map.of("sortBy", "NAME", "sortOrder", "DESCENDING"),
                service.listProjects(REGION, "NAME", "DESCENDING"));
        assertPagesMatchServiceOrder(Map.of("sortBy", "CREATED_TIME", "sortOrder", "ASCENDING"),
                service.listProjects(REGION, "CREATED_TIME", "ASCENDING"));
        assertPagesMatchServiceOrder(Map.of("sortBy", "LAST_MODIFIED_TIME", "sortOrder", "DESCENDING"),
                service.listProjects(REGION, "LAST_MODIFIED_TIME", "DESCENDING"));
    }

    @Test
    void onlyReturnsContinuationWhenMoreThanOneHundredProjectsExist() throws Exception {
        assertFalse(listPage(Map.of()).containsKey("nextToken"));
        IntStream.range(0, 100).forEach(index -> createProject(REGION,
                "project-%03d".formatted(index), null, null));

        Map<String, Object> page = listPage(Map.of("sortBy", "NAME"));
        assertEquals(100, ((List<?>) page.get("projects")).size());
        assertFalse(page.containsKey("nextToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuesThroughThreePages() throws Exception {
        IntStream.range(0, 201).forEach(index -> createProject(REGION,
                "project-%03d".formatted(index), null, null));

        List<String> names = new ArrayList<>();
        String token = null;
        for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
            Map<String, String> request = new HashMap<>(Map.of("sortBy", "NAME"));
            if (token != null) {
                request.put("nextToken", token);
            }
            Map<String, Object> page = listPage(request);
            List<String> projects = (List<String>) page.get("projects");
            assertEquals(pageIndex < 2 ? 100 : 1, projects.size());
            names.addAll(projects);
            token = (String) page.get("nextToken");
        }
        assertNull(token);
        assertEquals(service.listProjects(REGION, "NAME", null), names);
    }

    @Test
    void rejectsInvalidAndMismatchedContinuationTokens() throws Exception {
        IntStream.range(0, 101).forEach(index -> createProject(REGION,
                "project-%03d".formatted(index), null, null));
        String token = (String) listPage(Map.of("sortBy", "NAME", "sortOrder", "ASCENDING"))
                .get("nextToken");
        assertNotNull(token);

        assertInvalidToken(Map.of("sortBy", "NAME", "sortOrder", "ASCENDING", "nextToken", "invalid?"),
                REGION);
        assertInvalidToken(Map.of("sortBy", "NAME", "sortOrder", "ASCENDING", "nextToken", ""),
                REGION);
        assertInvalidToken(Map.of("sortBy", "NAME", "sortOrder", "DESCENDING", "nextToken", token),
                REGION);
        assertInvalidToken(Map.of("sortBy", "NAME", "sortOrder", "ASCENDING", "nextToken", token),
                "eu-west-1");

        ObjectNode cursor = (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(token));
        cursor.put("sortValue", 1.0);
        assertInvalidToken(Map.of("sortBy", "NAME", "sortOrder", "ASCENDING", "nextToken",
                Base64Url.encode(mapper.writeValueAsBytes(cursor))), REGION);

        String timeToken = (String) listPage(Map.of("sortBy", "CREATED_TIME"))
                .get("nextToken");
        ObjectNode timeCursor = (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(timeToken));
        timeCursor.put("sortValue", "invalid");
        assertInvalidToken(Map.of("sortBy", "CREATED_TIME", "nextToken",
                Base64Url.encode(mapper.writeValueAsBytes(timeCursor))), REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "123", "{}"})
    void rejectsNonTextualContinuationTokens(String value) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.set("nextToken", mapper.readTree(value));

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("ListProjects", request, REGION, ACCOUNT));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT", "NAME", "CREATED_TIME"})
    void continuesAfterDeletingTheLastProjectOnThePreviousPage(String sortBy) throws Exception {
        IntStream.range(0, 101).forEach(index -> createProject(REGION,
                "project-%03d".formatted(index), 1.0, null));
        Map<String, String> request = switch (sortBy) {
            case "NAME" -> Map.of("sortBy", "NAME", "sortOrder", "ASCENDING");
            case "CREATED_TIME" -> Map.of("sortBy", "CREATED_TIME", "sortOrder", "DESCENDING");
            default -> Map.of();
        };
        String token = (String) listPage(request).get("nextToken");
        assertNotNull(token);

        service.deleteProject(REGION, "project-099");
        Map<String, String> nextRequest = new HashMap<>(request);
        nextRequest.put("nextToken", token);
        Map<String, Object> nextPage = listPage(nextRequest);
        assertEquals(List.of("project-100"), nextPage.get("projects"));
        assertFalse(nextPage.containsKey("nextToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsCursorSortValueWhenProjectChangesDuringPageConstruction() throws Exception {
        IntStream.range(0, 101).forEach(index -> createProject(REGION,
                "project-%03d".formatted(index), (double) index, (double) index));
        Project changing = new Project() {
            private boolean changed;

            @Override
            public String getName() {
                if (!changed) {
                    setLastModified(1000.0);
                    changed = true;
                }
                return super.getName();
            }
        };
        changing.setName("project-099");
        changing.setCreated(99.0);
        changing.setLastModified(99.0);
        Field field = CodeBuildService.class.getDeclaredField("projects");
        field.setAccessible(true);
        Map<String, Map<String, Project>> regions =
                (Map<String, Map<String, Project>>) field.get(service);
        regions.get(REGION).put("project-099", changing);

        Map<String, Object> first = listPage(Map.of("sortBy", "LAST_MODIFIED_TIME"));
        String token = (String) first.get("nextToken");
        assertNotNull(token);
        assertEquals(99.0, mapper.readTree(Base64.getUrlDecoder().decode(token))
                .path("sortValue").asDouble());
        Map<String, Object> second = listPage(Map.of("sortBy", "LAST_MODIFIED_TIME", "nextToken", token));
        assertTrue(((List<String>) second.get("projects")).contains("project-100"));
    }

    @SuppressWarnings("unchecked")
    private void assertPagesMatchServiceOrder(Map<String, String> options, List<String> expected) throws Exception {
        Map<String, Object> first = listPage(options);
        assertEquals(100, ((List<?>) first.get("projects")).size());
        String token = (String) first.get("nextToken");
        assertNotNull(token);

        Map<String, String> nextRequest = new HashMap<>(options);
        nextRequest.put("nextToken", token);
        Map<String, Object> second = listPage(nextRequest);
        assertFalse(second.containsKey("nextToken"));
        List<String> names = new ArrayList<>((List<String>) first.get("projects"));
        names.addAll((List<String>) second.get("projects"));
        assertEquals(expected, names);
    }

    private void assertInvalidToken(Map<String, String> request, String region) {
        AwsException error = assertThrows(AwsException.class, () -> listPage(request, region));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Map<String, String> request) throws Exception {
        return (List<String>) listPage(request).get("projects");
    }

    private Map<String, Object> listPage(Map<String, String> request) throws Exception {
        return listPage(request, REGION);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listPage(Map<String, String> request, String region) throws Exception {
        try (Response response = handler.handle("ListProjects", mapper.valueToTree(request), region, ACCOUNT)) {
            assertEquals(200, response.getStatus());
            return (Map<String, Object>) response.getEntity();
        }
    }

    private void createProject(String region, String name, Double created, Double modified) {
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        Project project = service.createProject(region, ACCOUNT, name, null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);
        project.setCreated(created);
        project.setLastModified(modified);
    }
}
