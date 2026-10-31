package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class CodeBuildBuildListSortingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject CodeBuildService service;
    @InjectMock CodeBuildRunner runner;

    private String projectName;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createBuilds() {
        projectName = "sort-builds-" + UUID.randomUUID();
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        service.createProject(REGION, ACCOUNT, projectName, null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);
        for (int number = 1; number <= 12; number++) {
            Build build = service.startBuild(REGION, ACCOUNT, projectName, null,
                    null, null, null, null, null, null);
            service.getBuild(REGION, ACCOUNT, build.getId()).setStartTime(1000.0 + number);
        }
    }

    @AfterEach
    void deleteProject() {
        service.deleteProject(REGION, projectName);
    }

    @Test
    void listBuildsSortsByBuildIdOverTheWire() {
        List<String> expected = IntStream.rangeClosed(1, 12)
                .mapToObj(number -> projectName + ":" + number)
                .sorted()
                .toList();

        assertEquals(expected, listBuilds("ASCENDING"));
        assertEquals(expected.reversed(), listBuilds("DESCENDING"));
        assertEquals(expected.reversed(), listBuilds(null));
    }

    @Test
    void listBuildsForProjectSortsNumericallyOverTheWire() {
        List<String> expected = IntStream.rangeClosed(1, 12)
                .mapToObj(number -> projectName + ":" + number)
                .toList();

        assertEquals(expected, listBuildsForProject("ASCENDING"));
        assertEquals(expected.reversed(), listBuildsForProject("DESCENDING"));
        assertEquals(expected.reversed(), listBuildsForProject(null));
    }

    @Test
    void buildListContinuationUsesJsonWireShape() {
        String region = "eu-west-3";
        String account = String.format(Locale.ROOT, "78%010d",
                ThreadLocalRandom.current().nextLong(10_000_000_000L));
        String auth = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261027/" + region + "/codebuild/aws4_request";
        String isolatedProject = "page-builds-" + UUID.randomUUID();
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        service.createProject(region, account, isolatedProject, null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::" + account + ":role/codebuild",
                null, null, null, null, null, null, null);
        try {
            for (int number = 0; number < 101; number++) {
                service.startBuild(region, account, isolatedProject, null,
                        null, null, null, null, null, null);
            }

            JsonPath first = given().header("Authorization", auth)
                    .header("X-Amz-Target", "CodeBuild_20161006.ListBuildsForProject")
                    .contentType(CONTENT_TYPE).body(Map.of("projectName", isolatedProject))
                    .post("/").then().statusCode(200).extract().jsonPath();
            assertEquals(100, first.getList("ids", String.class).size());
            String token = first.getString("nextToken");
            assertNotNull(token);

            JsonPath last = given().header("Authorization", auth)
                    .header("X-Amz-Target", "CodeBuild_20161006.ListBuildsForProject")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("projectName", isolatedProject, "nextToken", token))
                    .post("/").then().statusCode(200).extract().jsonPath();
            assertEquals(List.of(isolatedProject + ":1"), last.getList("ids", String.class));
            assertNull(last.getString("nextToken"));
        } finally {
            service.deleteProject(region, isolatedProject);
        }
    }

    @Test
    void invalidSortOrderIsRejectedWithoutChangingBuilds() {
        List<String> allBuilds = listBuilds("DESCENDING");
        List<String> projectBuilds = listBuildsForProject("DESCENDING");

        given().header("X-Amz-Target", "CodeBuild_20161006.ListBuilds")
                .contentType(CONTENT_TYPE).body("{\"sortOrder\":\"UNKNOWN\"}")
                .post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"))
                .body("message", containsString("Invalid sortOrder"));
        assertEquals(allBuilds, listBuilds("DESCENDING"));
        assertEquals(projectBuilds, listBuildsForProject("DESCENDING"));

        given().header("X-Amz-Target", "CodeBuild_20161006.ListBuildsForProject")
                .contentType(CONTENT_TYPE)
                .body("{\"projectName\":\"" + projectName + "\",\"sortOrder\":\"UNKNOWN\"}")
                .post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"))
                .body("message", containsString("Invalid sortOrder"));
        assertEquals(allBuilds, listBuilds("DESCENDING"));
        assertEquals(projectBuilds, listBuildsForProject("DESCENDING"));
    }

    private List<String> listBuilds(String sortOrder) {
        List<String> ids = new ArrayList<>();
        String token = null;
        do {
            Map<String, String> request = new HashMap<>();
            if (sortOrder != null) {
                request.put("sortOrder", sortOrder);
            }
            if (token != null) {
                request.put("nextToken", token);
            }
            JsonPath page = given().header("X-Amz-Target", "CodeBuild_20161006.ListBuilds")
                    .contentType(CONTENT_TYPE).body(request)
                    .post("/").then().statusCode(200).extract().jsonPath();
            ids.addAll(page.getList("ids", String.class));
            token = page.getString("nextToken");
        } while (token != null);
        return ids.stream().filter(id -> id.startsWith(projectName + ":")).toList();
    }

    private List<String> listBuildsForProject(String sortOrder) {
        return given().header("X-Amz-Target", "CodeBuild_20161006.ListBuildsForProject")
                .contentType(CONTENT_TYPE)
                .body("{\"projectName\":\"" + projectName + "\""
                        + (sortOrder == null ? "" : ",\"sortOrder\":\"" + sortOrder + "\"") + "}")
                .post("/").then().statusCode(200)
                .extract().jsonPath().getList("ids", String.class);
    }
}
