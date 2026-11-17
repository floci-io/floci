package io.floci.aws.services.codedeploy;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class CodeDeployTagsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createDeploymentGroupStoresItsTags() {
        String application = "tag-app-7949-" + UUID.randomUUID();
        String group = "tag-dg-7949-" + UUID.randomUUID();
        String groupArn = "arn:aws:codedeploy:us-east-1:000000000000:deploymentgroup:"
                + application + "/" + group;
        deleteApplicationLater(application);
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("{\"applicationName\":\"" + application + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        deleteDeploymentGroupLater(application, group);
        untagLater(groupArn);
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "deploymentGroupName": "%s",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy",
                    "tags": [{"Key": "team", "Value": "red"}]
                }
                """.formatted(application, group))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceArn\":\"" + groupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("team"))
            .body("Tags[0].Value", equalTo("red"));
    }

    @Test
    void explicitNullTagsRemainOptional() {
        String application = "null-tag-app-7949-" + UUID.randomUUID();
        String group = "null-tag-group-7949-" + UUID.randomUUID();
        deleteApplicationLater(application);
        deleteDeploymentGroupLater(application, group);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("{\"applicationName\":\"" + application + "\",\"tags\":null}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "deploymentGroupName": "%s",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy",
                    "tags": null
                }
                """.formatted(application, group))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createApplicationRejectsUnmodeledTagShapes() {
        String[] invalidTags = {
            "[{\"key\":\"team\",\"value\":\"red\"}]",
            "[{\"Key\":123,\"Value\":\"red\"}]",
            "[{\"Key\":\"team\",\"Value\":true}]",
            "{\"0\":{\"Key\":\"team\",\"Value\":\"red\"}}"
        };
        for (int i = 0; i < invalidTags.length; i++) {
            String application = "invalid-tag-app-7949-" + UUID.randomUUID();
            String applicationArn = "arn:aws:codedeploy:us-east-1:000000000000:application:" + application;
            deleteApplicationLater(application);
            untagLater(applicationArn);
            given()
                .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
                .contentType(CONTENT_TYPE)
                .body("{\"applicationName\":\"" + application + "\",\"tags\":" + invalidTags[i] + "}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidTagsToAddException"));

            given()
                .header("X-Amz-Target", "CodeDeploy_20141006.GetApplication")
                .contentType(CONTENT_TYPE)
                .body("{\"applicationName\":\"" + application + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ApplicationDoesNotExistException"));

            given()
                .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
                .contentType(CONTENT_TYPE)
                .body("{\"ResourceArn\":\"" + applicationArn + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Tags", hasSize(0));
        }
    }

    @Test
    void createDeploymentGroupAndTagResourceRejectUnmodeledTagShapes() {
        String application = "invalid-tag-group-app-7949-" + UUID.randomUUID();
        String group = "invalid-tag-group-7949-" + UUID.randomUUID();
        String applicationArn = "arn:aws:codedeploy:us-east-1:000000000000:application:" + application;
        String groupArn = "arn:aws:codedeploy:us-east-1:000000000000:deploymentgroup:"
                + application + "/" + group;
        deleteApplicationLater(application);
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("{\"applicationName\":\"" + application + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        deleteDeploymentGroupLater(application, group);
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.CreateDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "applicationName": "%s",
                    "deploymentGroupName": "%s",
                    "serviceRoleArn": "arn:aws:iam::000000000000:role/codedeploy",
                    "tags": {"0": {"Key": "team", "Value": "red"}}
                }
                """.formatted(application, group))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidTagsToAddException"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.GetDeploymentGroup")
            .contentType(CONTENT_TYPE)
            .body("{\"applicationName\":\"" + application
                    + "\",\"deploymentGroupName\":\"" + group + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("DeploymentGroupDoesNotExistException"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceArn\":\"" + groupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));

        untagLater(applicationArn);
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "%s",
                    "Tags": [{"key": "team", "value": "red"}]
                }
                """.formatted(applicationArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidTagsToAddException"));

        given()
            .header("X-Amz-Target", "CodeDeploy_20141006.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceArn\":\"" + applicationArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    void listOnPremisesInstancesRejectsObjectTagFilters() {
        assertMalformed("ListOnPremisesInstances",
                "{\"tagFilters\":{\"0\":{\"Key\":\"team\",\"Value\":\"red\"}}}",
                "InvalidTagFilterException");
    }

    @Test
    void listOnPremisesInstancesRejectsUnknownFilterType() {
        assertMalformed("ListOnPremisesInstances",
                "{\"tagFilters\":[{\"Key\":\"team\",\"Type\":\"UNKNOWN\"}]}",
                "InvalidTagFilterException");
    }

    @Test
    void addTagsToOnPremisesInstancesRejectsObjectTags() {
        assertMalformed("AddTagsToOnPremisesInstances",
                "{\"instanceNames\":[],\"tags\":{\"0\":{\"Key\":\"team\",\"Value\":\"red\"}}}",
                "InvalidTagException");
    }

    @Test
    void removeTagsFromOnPremisesInstancesRejectsObjectTags() {
        assertMalformed("RemoveTagsFromOnPremisesInstances",
                "{\"instanceNames\":[],\"tags\":{\"0\":{\"Key\":\"team\",\"Value\":\"red\"}}}",
                "InvalidTagException");
    }

    private void assertMalformed(String action, String body, String expectedType) {
        given()
            .header("X-Amz-Target", "CodeDeploy_20141006." + action)
            .contentType(CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo(expectedType));
    }

    private void deleteApplicationLater(String application) {
        cleanup.register(() -> given()
                .header("X-Amz-Target", "CodeDeploy_20141006.DeleteApplication")
                .contentType(CONTENT_TYPE)
                .body("{\"applicationName\":\"" + application + "\"}")
                .post("/"));
    }

    private void deleteDeploymentGroupLater(String application, String group) {
        cleanup.register(() -> given()
                .header("X-Amz-Target", "CodeDeploy_20141006.DeleteDeploymentGroup")
                .contentType(CONTENT_TYPE)
                .body("{\"applicationName\":\"" + application + "\",\"deploymentGroupName\":\"" + group + "\"}")
                .post("/"));
    }

    private void untagLater(String arn) {
        cleanup.register(() -> given()
                .header("X-Amz-Target", "CodeDeploy_20141006.UntagResource")
                .contentType(CONTENT_TYPE)
                .body("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"team\",\"123\",\"\"]}")
                .post("/"));
    }
}
