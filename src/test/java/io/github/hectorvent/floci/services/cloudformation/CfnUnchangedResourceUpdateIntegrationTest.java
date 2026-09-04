package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * UpdateStack must leave an unchanged resource alone, whatever its type.
 *
 * <p>{@code provision()} re-runs for every resource on every update, changed or not, so a
 * provisioner that calls create unconditionally either collides with itself (rolling the stack
 * back) or quietly mints a new physical id, orphaning everything that referenced the old one.
 * Each case here builds a stack holding one resource plus a throwaway queue, updates only the
 * queue, and asserts both that the stack reached UPDATE_COMPLETE and that the untouched resource
 * kept its physical id.
 *
 * <p>Regression cover for "CloudFormation update recreates unchanged named resources"
 * (floci-io/floci#2134).
 */
@QuarkusTest
class CfnUnchangedResourceUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-west-2/cloudformation/aws4_request";

    private static String stackWith(String namedResourceJson, String queueName) {
        return """
                {
                  "Resources": {
                    %s,
                    "ChurnQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s"}
                    }
                  }
                }
                """.formatted(namedResourceJson, queueName);
    }

    /**
     * Pulls the PhysicalResourceId of one logical resource out of a DescribeStackResources body.
     * The member ordering is not guaranteed, so match the pair rather than a fixed offset.
     */
    private static String physicalIdOf(String describeBody, String logicalId) {
        Matcher m = Pattern.compile(
                "<member>(?:(?!</member>).)*?<LogicalResourceId>" + Pattern.quote(logicalId)
                        + "</LogicalResourceId>(?:(?!</member>).)*?</member>", Pattern.DOTALL)
                .matcher(describeBody);
        if (!m.find()) {
            return null;
        }
        Matcher pid = Pattern.compile("<PhysicalResourceId>(.*?)</PhysicalResourceId>", Pattern.DOTALL)
                .matcher(m.group());
        return pid.find() ? pid.group(1) : null;
    }

    private String namedPhysicalId(String stackName) {
        String body = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStackResources").formParam("StackName", stackName)
            .when().post("/").then().statusCode(200)
            .extract().body().asString();
        return physicalIdOf(body, "Named");
    }

    /**
     * Creates the stack, updates it with only the churn queue renamed, then asserts both that the
     * update completed and that the untouched resource kept its physical id. The second assertion
     * is the load-bearing one: a provisioner that re-creates the resource under a fresh id can
     * still report UPDATE_COMPLETE while silently orphaning whatever referenced the old id.
     */
    private void assertUnchangedResourceSurvivesUpdate(String label, String namedResourceJson) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "probe-" + label + "-" + suffix;
        String body = namedResourceJson.replace("SUFFIX", suffix);

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack").formParam("StackName", stackName)
            .formParam("TemplateBody", stackWith(body, "probe-q-a-" + suffix))
        .when().post("/").then().statusCode(200);

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks").formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        String before = namedPhysicalId(stackName);
        assertNotNull(before, "no PhysicalResourceId for Named after create");

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack").formParam("StackName", stackName)
            .formParam("TemplateBody", stackWith(body, "probe-q-b-" + suffix))
        .when().post("/").then().statusCode(200);

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks").formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")));

        assertEquals(before, namedPhysicalId(stackName),
                label + ": unchanged resource was re-created under a new physical id");
    }

    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-west-2/ec2/aws4_request";

    /** Runs create-then-update and hands back the Named physical id before and after. */
    private String[] createThenUpdate(String label, String createBody, String updateBody) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "probe-" + label + "-" + suffix;

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack").formParam("StackName", stackName)
            .formParam("TemplateBody", stackWith(createBody.replace("SUFFIX", suffix), "probe-q-a-" + suffix))
        .when().post("/").then().statusCode(200);

        String before = namedPhysicalId(stackName);
        assertNotNull(before, "no PhysicalResourceId for Named after create");

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack").formParam("StackName", stackName)
            .formParam("TemplateBody", stackWith(updateBody.replace("SUFFIX", suffix), "probe-q-b-" + suffix))
        .when().post("/").then().statusCode(200);

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks").formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")));

        return new String[] {before, namedPhysicalId(stackName)};
    }

    private static final String SG_WITH_INGRESS = """
            "Named": {
              "Type": "AWS::EC2::SecurityGroup",
              "Properties": {
                "GroupName": "probe-sg-SUFFIX",
                "GroupDescription": "probe DESCRIPTION",
                "SecurityGroupIngress": [
                  {"IpProtocol": "tcp", "FromPort": 22, "ToPort": 22, "CidrIp": "10.0.0.0/8"}
                ]
              }
            }""";

    /**
     * AuthorizeSecurityGroupIngress appends without a duplicate check, so re-running provision on a
     * reused group stacks another copy of every inline rule on each update. Retaining the group id
     * is not enough on its own: the group has to still describe the rules the template declares,
     * once each.
     */
    /**
     * A group can carry rules from standalone AWS::EC2::SecurityGroupIngress resources as well as
     * its own inline ones. Reconciling the inline set by clearing the group first would revoke
     * those too, so a stack update unrelated to them would silently close their ports.
     */
    @Test
    void anUpdateLeavesRulesOwnedByStandaloneResourcesAlone() {
        String body = """
                "Named": {
                  "Type": "AWS::EC2::SecurityGroup",
                  "Properties": {
                    "GroupName": "probe-sg-mixed-SUFFIX",
                    "GroupDescription": "probe mixed",
                    "SecurityGroupIngress": [
                      {"IpProtocol": "tcp", "FromPort": 22, "ToPort": 22, "CidrIp": "10.0.0.0/8"}
                    ]
                  }
                },
                "ExtraRule": {
                  "Type": "AWS::EC2::SecurityGroupIngress",
                  "Properties": {
                    "GroupId": {"Ref": "Named"},
                    "IpProtocol": "tcp",
                    "FromPort": 8080,
                    "ToPort": 8080,
                    "CidrIp": "10.9.0.0/16"
                  }
                }""";
        String[] ids = createThenUpdate("sg-mixed", body, body);
        assertEquals(ids[0], ids[1], "unchanged security group was re-created under a new physical id");

        String described = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeSecurityGroups").formParam("GroupId.1", ids[1])
            .when().post("/").then().statusCode(200)
            .extract().body().asString();

        assertEquals(1, described.split("<fromPort>22</fromPort>", -1).length - 1,
                "inline rule should appear exactly once after an update");
        assertEquals(1, described.split("<fromPort>8080</fromPort>", -1).length - 1,
                "the standalone resource's rule was revoked by the inline reconciliation");
    }

    @Test
    void securityGroupInlineRulesAreNotDuplicatedByAnUpdate() {
        String body = SG_WITH_INGRESS.replace("DESCRIPTION", "stable");
        String[] ids = createThenUpdate("sg-rules", body, body);
        assertEquals(ids[0], ids[1], "unchanged security group was re-created under a new physical id");

        String described = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeSecurityGroups").formParam("GroupId.1", ids[1])
            .when().post("/").then().statusCode(200)
            .extract().body().asString();

        int occurrences = described.split("<fromPort>22</fromPort>", -1).length - 1;
        assertEquals(1, occurrences,
                "inline ingress rule appears " + occurrences + " times after one update; "
                        + "provision re-authorized it on the reused group");
    }

    @Test
    void logGroup() {
        assertUnchangedResourceSurvivesUpdate("loggroup", """
                "Named": {
                  "Type": "AWS::Logs::LogGroup",
                  "Properties": {"LogGroupName": "/probe/log-SUFFIX"}
                }""");
    }

    @Test
    void snsTopic() {
        assertUnchangedResourceSurvivesUpdate("sns", """
                "Named": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {"TopicName": "probe-topic-SUFFIX"}
                }""");
    }

    @Test
    void sqsQueue() {
        assertUnchangedResourceSurvivesUpdate("sqs", """
                "Named": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {"QueueName": "probe-queue-SUFFIX"}
                }""");
    }

    @Test
    void ecrRepository() {
        assertUnchangedResourceSurvivesUpdate("ecr", """
                "Named": {
                  "Type": "AWS::ECR::Repository",
                  "Properties": {"RepositoryName": "probe-repo-SUFFIX"}
                }""");
    }

    @Test
    void kmsAlias() {
        assertUnchangedResourceSurvivesUpdate("kms", """
                "Key": {"Type": "AWS::KMS::Key", "Properties": {}},
                "Named": {
                  "Type": "AWS::KMS::Alias",
                  "Properties": {"AliasName": "alias/probe-SUFFIX", "TargetKeyId": {"Ref": "Key"}}
                }""");
    }

    @Test
    void kinesisStream() {
        assertUnchangedResourceSurvivesUpdate("kinesis", """
                "Named": {
                  "Type": "AWS::Kinesis::Stream",
                  "Properties": {"Name": "probe-stream-SUFFIX", "ShardCount": 1}
                }""");
    }

    @Test
    void securityGroup() {
        assertUnchangedResourceSurvivesUpdate("sg", """
                "Vpc": {
                  "Type": "AWS::EC2::VPC",
                  "Properties": {"CidrBlock": "10.99.0.0/16"}
                },
                "Named": {
                  "Type": "AWS::EC2::SecurityGroup",
                  "Properties": {
                    "GroupName": "probe-sg-SUFFIX",
                    "GroupDescription": "probe",
                    "VpcId": {"Ref": "Vpc"}
                  }
                }""");
    }

    @Test
    void vpc() {
        assertUnchangedResourceSurvivesUpdate("vpc", """
                "Named": {
                  "Type": "AWS::EC2::VPC",
                  "Properties": {"CidrBlock": "10.98.0.0/16"}
                }""");
    }

    @Test
    void s3Bucket() {
        assertUnchangedResourceSurvivesUpdate("s3", """
                "Named": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {"BucketName": "probe-bucket-SUFFIX"}
                }""");
    }
}
