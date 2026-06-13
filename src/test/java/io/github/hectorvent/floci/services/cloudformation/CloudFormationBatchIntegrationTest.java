package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CloudFormationBatchIntegrationTest {

    private static final String BATCH_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/batch/aws4_request";

    @Test
    void createStackWithBatchResourcesRegistersUsableQueueAndDefinition() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String computeName = "cfn-batch-ce-" + suffix;
        String queueName = "cfn-batch-queue-" + suffix;
        String definitionName = "cfn-batch-job-" + suffix;
        String stackName = "cfn-batch-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Compute": {
                      "Type": "AWS::Batch::ComputeEnvironment",
                      "Properties": {
                        "ComputeEnvironmentName": "%s",
                        "Type": "MANAGED",
                        "ComputeResources": {
                          "Type": "FARGATE",
                          "MaxvCpus": 4,
                          "Subnets": ["subnet-local"],
                          "SecurityGroupIds": ["sg-local"]
                        }
                      }
                    },
                    "Queue": {
                      "Type": "AWS::Batch::JobQueue",
                      "Properties": {
                        "JobQueueName": "%s",
                        "Priority": 1,
                        "ComputeEnvironmentOrder": [{
                          "Order": 1,
                          "ComputeEnvironment": {"Ref": "Compute"}
                        }]
                      }
                    },
                    "Definition": {
                      "Type": "AWS::Batch::JobDefinition",
                      "Properties": {
                        "JobDefinitionName": "%s",
                        "Type": "container",
                        "PlatformCapabilities": ["FARGATE"],
                        "ContainerProperties": {
                          "Image": "public.ecr.aws/example/job:latest",
                          "Command": ["Ref::inputKey"],
                          "Environment": [{"Name":"FROM_CFN","Value":"yes"}],
                          "ResourceRequirements": [
                            {"Type":"VCPU","Value":"1"},
                            {"Type":"MEMORY","Value":"512"}
                          ]
                        }
                      }
                    }
	                  },
	                  "Outputs": {
	                    "ComputeArn": {"Value": {"Fn::GetAtt": ["Compute", "ComputeEnvironmentArn"]}},
	                    "QueueArn": {"Value": {"Fn::GetAtt": ["Queue", "JobQueueArn"]}},
	                    "DefinitionArn": {"Value": {"Fn::GetAtt": ["Definition", "JobDefinitionArn"]}}
	                  }
	                }
	                """.formatted(computeName, queueName, definitionName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("compute-environment/" + computeName))
            .body(containsString("job-queue/" + queueName))
            .body(containsString("job-definition/" + definitionName + ":1"));

        givenBatchJson("{\"jobQueues\":[\"%s\"]}".formatted(queueName))
        .when()
            .post("/v1/describejobqueues")
        .then()
            .statusCode(200)
            .body("jobQueues", hasSize(1))
            .body("jobQueues[0].jobQueueName", equalTo(queueName));

        String jobId = givenBatchJson("""
                {
                  "jobName": "cfn-batch-submit-%s",
                  "jobQueue": "%s",
                  "jobDefinition": "%s",
                  "parameters": {"inputKey":"from-cfn.json"}
                }
                """.formatted(suffix, queueName, definitionName))
        .when()
            .post("/v1/submitjob")
        .then()
            .statusCode(200)
            .body("jobId", notNullValue())
            .extract().path("jobId");

        givenBatchJson("{\"jobs\":[\"%s\"]}".formatted(jobId))
        .when()
            .post("/v1/describejobs")
        .then()
            .statusCode(200)
            .body("jobs[0].status", equalTo("SUCCEEDED"))
            .body("jobs[0].container.command[0]", equalTo("from-cfn.json"))
            .body("jobs[0].container.environment.find { it.name == 'FROM_CFN' }.value", equalTo("yes"));
    }

    @Test
    void createStackWithBatchArrayTargetPreservesMetadataWithoutArrayFanout() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-batch-array-target-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Rule": {
                      "Type": "AWS::Events::Rule",
                      "Properties": {
                        "Name": "%s",
                        "EventPattern": {"source": ["local.test"]},
                        "Targets": [{
                          "Id": "batch-target",
                          "Arn": "arn:aws:batch:us-east-1:000000000000:job-queue/local",
                          "BatchParameters": {
                            "JobDefinition": "local-job",
                            "JobName": "array-job",
                            "ArrayProperties": {"Size": 2}
                          }
                        }]
                      }
                    }
                  }
                }
                """.formatted("cfn-batch-array-rule-" + suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CREATE_COMPLETE"));
    }

    private static io.restassured.specification.RequestSpecification givenBatchJson(String body) {
        return given()
                .header("Authorization", BATCH_AUTH)
                .contentType("application/json")
                .body(body);
    }
}
