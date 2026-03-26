package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationIntegrationTest {

    @Test
    void createStack_withS3AndSqs() {
        String template = """
            {
              "Mappings": {
                "Env": {
                  "us-east-1": {
                    "Name": "test"
                  }
                }
              },
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": {
                       "Fn::Sub": ["cf-${env}-bucket", {
                         "env": {
                            "Fn::FindInMap": ["Env", { "Ref" : "AWS::Region" }, "Name"]
                         }
                       }]
                    }
                  }
                },
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cf-test-queue"
                  }
                }
              }
            }
            """;

        // 1. Create Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "test-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // 2. Verify S3 Bucket exists
        given()
            .header("Host", "cf-test-bucket.localhost")
        .when()
            .get("/")
        .then()
            .statusCode(200);

        // 3. Verify SQS Queue exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cf-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cf-test-queue"));
        
        // 4. Describe Stacks
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "test-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackName>test-stack</StackName>"))
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
    }

    @Test
    void deleteChangeSet_removesChangeSet() {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "cs-delete-test-bucket"
                  }
                }
              }
            }
            """;

        // 1. Create a ChangeSet (implicitly creates the stack)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"));

        // 2. Verify ChangeSet exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ChangeSetName>my-changeset</ChangeSetName>"));

        // 3. Delete the ChangeSet
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteChangeSetResult/>"));

        // 4. Verify ChangeSet no longer exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ChangeSetNotFoundException"));

        // 5. Verify ChangeSet is absent from ListChangeSets
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListChangeSets")
            .formParam("StackName", "cs-delete-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("my-changeset")));
    }

    @Test
    void deleteChangeSet_nonExistentChangeSet_returnsError() {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "cs-error-test-bucket"
                  }
                }
              }
            }
            """;

        // Create a stack via CreateChangeSet so the stack exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "cs-error-stack")
            .formParam("ChangeSetName", "existing-changeset")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Attempt to delete a changeset that does not exist
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteChangeSet")
            .formParam("StackName", "cs-error-stack")
            .formParam("ChangeSetName", "nonexistent-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ChangeSetNotFoundException"));
    }
}
