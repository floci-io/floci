package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeBuildIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createProject() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-build-project",
                    "description": "Integration test project",
                    "source": {
                        "type": "S3",
                        "location": "my-bucket/source.zip"
                    },
                    "artifacts": {
                        "type": "NO_ARTIFACTS"
                    },
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/standard:7.0",
                        "computeType": "BUILD_GENERAL1_SMALL"
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("project.name", equalTo("my-build-project"))
            .body("project.description", equalTo("Integration test project"))
            .body("project.arn", containsString("arn:aws:codebuild:"))
            .body("project.arn", containsString(":project/my-build-project"))
            .body("project.serviceRole", equalTo("arn:aws:iam::000000000000:role/codebuild-role"))
            .body("project.timeoutInMinutes", equalTo(60))
            .body("project.projectVisibility", equalTo("PRIVATE"));
    }

    @Test
    @Order(2)
    void createDuplicateProjectFails() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-build-project",
                    "source": {"type": "NO_SOURCE"},
                    "artifacts": {"type": "NO_ARTIFACTS"},
                    "environment": {
                        "type": "LINUX_CONTAINER",
                        "image": "aws/codebuild/standard:7.0",
                        "computeType": "BUILD_GENERAL1_SMALL"
                    },
                    "serviceRole": "arn:aws:iam::000000000000:role/codebuild-role"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(3)
    void batchGetProjects() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetProjects")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "names": [
                        "arn:aws:codebuild:us-east-1:000000000000:project/my-build-project",
                        "nonexistent-project"
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("projects", hasSize(1))
            .body("projects[0].name", equalTo("my-build-project"))
            .body("projectsNotFound", hasSize(1))
            .body("projectsNotFound[0]", equalTo("nonexistent-project"));
    }

    @Test
    @Order(4)
    void listProjects() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListProjects")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("projects", hasItem("my-build-project"));
    }

    @Test
    @Order(5)
    void updateProject() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.UpdateProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-build-project",
                    "description": "Updated description",
                    "timeoutInMinutes": 120
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("project.name", equalTo("my-build-project"))
            .body("project.description", equalTo("Updated description"))
            .body("project.timeoutInMinutes", equalTo(120));
    }

    @Test
    @Order(6)
    void createReportGroup() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.CreateReportGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "name": "my-report-group",
                    "type": "TEST",
                    "exportConfig": {
                        "exportConfigType": "NO_EXPORT"
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reportGroup.name", equalTo("my-report-group"))
            .body("reportGroup.type", equalTo("TEST"))
            .body("reportGroup.arn", containsString(":report-group/my-report-group"))
            .body("reportGroup.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(7)
    void listReportGroups() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListReportGroups")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reportGroups", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(8)
    void batchGetReportGroups() {
        // Fetch ARN first
        String arn = given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListReportGroups")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("reportGroups[0]");

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.BatchGetReportGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"reportGroupArns\": [\"" + arn + "\", \"arn:aws:codebuild:us-east-1:000000000000:report-group/nonexistent\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("reportGroups", hasSize(1))
            .body("reportGroupsNotFound", hasSize(1));
    }

    @Test
    @Order(9)
    void importAndListSourceCredentials() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ImportSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "token": "ghp_test_token",
                    "serverType": "GITHUB",
                    "authType": "OAUTH"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("arn", containsString(":token/github-"));

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("sourceCredentialsInfos", hasSize(greaterThanOrEqualTo(1)))
            .body("sourceCredentialsInfos[0].serverType", equalTo("GITHUB"))
            .body("sourceCredentialsInfos[0].authType", equalTo("OAUTH"));
    }

    @Test
    @Order(10)
    void listCuratedEnvironmentImages() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListCuratedEnvironmentImages")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("platforms", hasSize(greaterThanOrEqualTo(1)))
            .body("platforms[0].platform", notNullValue());
    }

    @Test
    @Order(11)
    void deleteSourceCredentials() {
        String arn = given()
            .header("X-Amz-Target", "CodeBuild_20161006.ListSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("sourceCredentialsInfos[0].arn");

        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteSourceCredentials")
            .contentType(CONTENT_TYPE)
            .body("{\"arn\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("arn", equalTo(arn));
    }

    @Test
    @Order(12)
    void deleteProject() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {"name": "my-build-project"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void deleteNonexistentProjectFails() {
        given()
            .header("X-Amz-Target", "CodeBuild_20161006.DeleteProject")
            .contentType(CONTENT_TYPE)
            .body("""
                {"name": "my-build-project"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ResourceNotFoundException"));
    }
}
