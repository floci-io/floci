package io.floci.aws.services.codebuild;

import io.floci.aws.services.codebuild.model.ProjectArtifacts;
import io.floci.aws.services.codebuild.model.ProjectEnvironment;
import io.floci.aws.services.codebuild.model.ProjectSource;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CodeBuildListProjectsPaginationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject CodeBuildService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listProjectsReturnsAtMostOneHundredNamesPerPage() {
        String prefix = "page-project-" + UUID.randomUUID() + "-";
        List<String> names = IntStream.range(0, 101)
                .mapToObj(index -> prefix + "%03d".formatted(index))
                .toList();
        List<String> created = new ArrayList<>();
        try {
            ProjectSource source = new ProjectSource();
            source.setType("NO_SOURCE");
            ProjectArtifacts artifacts = new ProjectArtifacts();
            artifacts.setType("NO_ARTIFACTS");
            for (String name : names) {
                service.createProject(REGION, ACCOUNT, name, null, source, null, null,
                        artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                        null, null, null, null, null, null, null);
                created.add(name);
            }

            List<String> allNames = new ArrayList<>();
            String nextToken = null;
            int pages = 0;
            do {
                Map<String, String> request = nextToken == null
                        ? Map.of("sortBy", "NAME", "sortOrder", "ASCENDING")
                        : Map.of("sortBy", "NAME", "sortOrder", "ASCENDING", "nextToken", nextToken);
                Response response = given().header("X-Amz-Target", "CodeBuild_20161006.ListProjects")
                        .contentType(CONTENT_TYPE).body(request)
                        .post("/").then().statusCode(200).extract().response();
                List<String> page = response.jsonPath().getList("projects", String.class);
                assertTrue(page.size() <= 100, "AWS ListProjects returns at most 100 names per page");
                allNames.addAll(page);
                nextToken = response.jsonPath().getString("nextToken");
                if (pages++ == 0) {
                    assertNotNull(nextToken, "101 projects must produce a continuation token");
                }
                assertTrue(pages < 10, "pagination must eventually end");
            } while (nextToken != null);

            assertEquals(names, allNames.stream().filter(name -> name.startsWith(prefix)).toList());
        } finally {
            for (String name : created) {
                service.deleteProject(REGION, name);
            }
        }
    }
}
