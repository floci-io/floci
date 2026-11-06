package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.sqs.model.Message;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code AWS::S3::Bucket}'s {@code NotificationConfiguration} reaches the bucket and delivers.
 *
 * <p>Before this, {@code S3CfnProvisioner} dropped the property, so a stack wiring a bucket to a
 * queue or a function reported {@code CREATE_COMPLETE} and no event was ever sent. Delivery is
 * asserted through SQS, which needs no container; the Lambda and SNS entries are asserted as stored
 * configuration.
 *
 * <p>The template has the shape a real SAM stack uses to avoid a circular dependency: the bucket
 * depends on the function through its notification, so the function's role and the permission's
 * {@code SourceArn} name the bucket by string instead of referring back to it.
 */
@QuarkusTest
class CloudFormationS3BucketNotificationIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    SqsService sqsService;

    @Inject
    ObjectMapper objectMapper;

    private static String template(String name, String notificationConfiguration) {
        return """
            {
              "Resources": {
                "Queue": { "Type": "AWS::SQS::Queue", "Properties": { "QueueName": "%1$s" } },
                "Topic": { "Type": "AWS::SNS::Topic", "Properties": { "TopicName": "%1$s" } },
                "Role": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {
                    "AssumeRolePolicyDocument": { "Version": "2012-10-17", "Statement": [
                      { "Effect": "Allow", "Principal": { "Service": "lambda.amazonaws.com" }, "Action": "sts:AssumeRole" } ] },
                    "Policies": [ { "PolicyName": "read-images", "PolicyDocument": { "Version": "2012-10-17", "Statement": [
                      { "Effect": "Allow", "Action": "s3:GetObject",
                        "Resource": { "Fn::Sub": "arn:${AWS::Partition}:s3:::%1$s/*" } } ] } } ]
                  }
                },
                "Fn": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%1$s",
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "Role": { "Fn::GetAtt": ["Role", "Arn"] },
                    "Code": { "ZipFile": "def handler(e, c): return 'ok'" }
                  }
                },
                "FnPermission": {
                  "Type": "AWS::Lambda::Permission",
                  "Properties": {
                    "FunctionName": { "Fn::GetAtt": ["Fn", "Arn"] },
                    "Action": "lambda:InvokeFunction",
                    "Principal": "s3.amazonaws.com",
                    "SourceAccount": { "Ref": "AWS::AccountId" },
                    "SourceArn": { "Fn::Sub": "arn:${AWS::Partition}:s3:::%1$s" }
                  }
                },
                "Bucket": {
                  "Type": "AWS::S3::Bucket",
                  "DependsOn": ["FnPermission"],
                  "Properties": { "BucketName": "%1$s" %2$s }
                }
              }
            }
            """.formatted(name, notificationConfiguration);
    }

    /** A queue for new objects under {@code uploads/}, a topic for restores, and the function for removals. */
    private static final String QUEUE_AND_FUNCTION = """
        , "NotificationConfiguration": {
            "QueueConfigurations": [ {
              "Event": "s3:ObjectCreated:*",
              "Queue": { "Fn::GetAtt": ["Queue", "Arn"] },
              "Filter": { "S3Key": { "Rules": [ { "Name": "prefix", "Value": "uploads/" } ] } }
            } ],
            "TopicConfigurations": [ {
              "Event": "s3:ObjectRestore:Completed",
              "Topic": { "Ref": "Topic" }
            } ],
            "LambdaConfigurations": [ {
              "Event": "s3:ObjectRemoved:*",
              "Function": { "Fn::GetAtt": ["Fn", "Arn"] }
            } ]
          }
        """;

    @Test
    void theBucketCarriesItsNotificationConfigurationAndDeliversThroughIt() throws Exception {
        String name = "cfn-notify-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");

        bucketGet(name, "notification")
            .body(containsString("<Queue>arn:aws:sqs:us-east-1:000000000000:" + name + "</Queue>"))
            .body(containsString("<Event>s3:ObjectCreated:*</Event>"))
            .body(containsString("<Name>prefix</Name>"))
            .body(containsString("<Value>uploads/</Value>"))
            .body(containsString("<CloudFunction>arn:aws:lambda:us-east-1:000000000000:function:" + name
                    + "</CloudFunction>"))
            .body(containsString("<Event>s3:ObjectRemoved:*</Event>"))
            .body(containsString("<Topic>arn:aws:sns:us-east-1:000000000000:" + name + "</Topic>"))
            .body(containsString("<Event>s3:ObjectRestore:Completed</Event>"));

        putObject(name, "elsewhere/skipped.txt");
        putObject(name, "uploads/delivered.txt");

        // S3 sends its test event when the configuration is put, then one record per matching object.
        List<String> keys = new ArrayList<>();
        int testEvents = 0;
        for (Message message : drain(name)) {
            JsonNode body = objectMapper.readTree(message.getBody());
            if (body.has("Records")) {
                JsonNode record = body.path("Records").get(0);
                assertEquals("ObjectCreated:Put", record.path("eventName").asText());
                keys.add(record.path("s3").path("object").path("key").asText());
            } else {
                assertEquals("s3:TestEvent", body.path("Event").asText());
                testEvents++;
            }
        }
        assertEquals(1, testEvents, "S3 validates the queue with one test event");
        assertEquals(List.of("uploads/delivered.txt"), keys, "the prefix filter selects the delivered objects");
    }

    /**
     * An {@code Fn::If} resolving to {@code AWS::NoValue} omits what it wraps: the false entry is
     * dropped from the list, and a false configuration is not declared at all, so it neither applies
     * nor clears anything.
     */
    @Test
    void conditionalEntriesAndConfigurationsFollowTheirConditions() throws Exception {
        String name = "cfn-notify-if-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, """
            {
              "Parameters": { "WithBucketNotifications": { "Type": "String", "Default": "true" } },
              "Conditions": {
                "Never": { "Fn::Equals": ["a", "b"] },
                "Notify": { "Fn::Equals": [{ "Ref": "WithBucketNotifications" }, "true"] }
              },
              "Resources": {
                "Queue": { "Type": "AWS::SQS::Queue", "Properties": { "QueueName": "%1$s" } },
                "Bucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "%1$s",
                    "NotificationConfiguration": { "Fn::If": ["Notify", {
                      "QueueConfigurations": [
                        { "Event": "s3:ObjectCreated:*", "Queue": { "Fn::GetAtt": ["Queue", "Arn"] } },
                        { "Fn::If": ["Never",
                          { "Event": "s3:ObjectRemoved:*", "Queue": { "Fn::GetAtt": ["Queue", "Arn"] } },
                          { "Ref": "AWS::NoValue" }] }
                      ] }, { "Ref": "AWS::NoValue" }] }
                  }
                }
              }
            }
            """.formatted(name));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");

        bucketGet(name, "notification")
            .body(containsString("<Event>s3:ObjectCreated:*</Event>"))
            .body(not(containsString("s3:ObjectRemoved")));
    }

    /**
     * A queue that does not exist fails the bucket as S3's destination validation does for
     * {@code PutBucketNotificationConfiguration}, rather than reporting a bucket that never delivers.
     */
    @Test
    void aDestinationThatDoesNotExistFailsTheStack() throws Exception {
        String name = "cfn-notify-missing-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, """
            , "NotificationConfiguration": { "QueueConfigurations": [ {
                "Event": "s3:ObjectCreated:*",
                "Queue": "arn:aws:sqs:us-east-1:000000000000:%s-absent" } ] }
            """.formatted(name)));
        CfnStackWaits.awaitStatus(stackId, "ROLLBACK_COMPLETE");
        given().when().head("/" + name).then().statusCode(404);

        String events = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackId)
        .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(events.contains("Unable to validate the following destination configurations"), events);
    }

    /**
     * An update whose destination S3 rejects fails before anything changed the bucket, rolls back
     * cleanly, and leaves the configuration the stack had.
     */
    @Test
    void anUpdateWithARejectedDestinationRollsBackToTheConfigurationItHad() throws Exception {
        String name = "cfn-notify-reject-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");

        stack("UpdateStack", name, template(name, """
            , "VersioningConfiguration": { "Status": "Enabled" },
              "NotificationConfiguration": { "QueueConfigurations": [ {
                "Event": "s3:ObjectCreated:*",
                "Queue": "arn:aws:sqs:us-east-1:000000000000:%s-absent" } ] }
            """.formatted(name)));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

        bucketGet(name, "notification")
            .body(containsString("<Queue>arn:aws:sqs:us-east-1:000000000000:" + name + "</Queue>"))
            .body(not(containsString("-absent")));
        bucketGet(name, "versioning").body(not(containsString("<Status>Enabled</Status>")));
    }

    /**
     * A property that fails after the new configuration was put has the configuration it replaced
     * put back, so the stack's UPDATE_ROLLBACK_COMPLETE describes the bucket truthfully.
     */
    @Test
    void anUpdateFailingAfterTheNotificationPutRestoresTheConfigurationItReplaced() throws Exception {
        String name = "cfn-notify-later-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");

        stack("UpdateStack", name, template(name, """
            , "VersioningConfiguration": { "Status": "Bogus" },
              "NotificationConfiguration": { "QueueConfigurations": [ {
                "Event": "s3:ObjectRemoved:*", "Queue": { "Fn::GetAtt": ["Queue", "Arn"] } } ] }
            """));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

        bucketGet(name, "notification")
            .body(containsString("<Event>s3:ObjectCreated:*</Event>"))
            .body(containsString("<Value>uploads/</Value>"))
            .body(containsString("<CloudFunction>"))
            .body(containsString("<TopicConfiguration>"));
    }

    @Test
    void aCreateFailingAfterTheBucketExistsDeletesTheBucket() throws Exception {
        String name = "cfn-notify-later-create-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION
                + ", \"VersioningConfiguration\": { \"Status\": \"Bogus\" }"));
        CfnStackWaits.awaitStatus(stackId, "ROLLBACK_COMPLETE");

        given().when().head("/" + name).then().statusCode(404);
    }

    /**
     * A configuration whose {@code Fn::If} chose {@code AWS::NoValue} was never applied, so the
     * stack never declared one: a configuration put outside the stack survives a later update.
     */
    @Test
    void aConfigurationThatWasConditionallyOmittedIsNotClearedByALaterUpdate() throws Exception {
        String name = "cfn-notify-omitted-" + Long.toString(System.nanoTime(), 36);
        String template = """
            {
              "Parameters": { "Notify": { "Type": "String", "Default": "false" },
                              "Owner": { "Type": "String", "Default": "%2$s" } },
              "Conditions": { "WithNotifications": { "Fn::Equals": [{ "Ref": "Notify" }, "true"] } },
              "Resources": {
                "Queue": { "Type": "AWS::SQS::Queue", "Properties": { "QueueName": "%1$s" } },
                "Bucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "%1$s",
                    "Tags": [ { "Key": "owner", "Value": { "Ref": "Owner" } } ],
                    "NotificationConfiguration": { "Fn::If": ["WithNotifications",
                      { "QueueConfigurations": [ { "Event": "s3:ObjectCreated:*",
                          "Queue": { "Fn::GetAtt": ["Queue", "Arn"] } } ] },
                      { "Ref": "AWS::NoValue" }] }
                  }
                }
              }
            }
            """;
        String stackId = stack("CreateStack", name, template.formatted(name, "first"));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");
        String queueArn = "arn:aws:sqs:us-east-1:000000000000:" + name;
        given().contentType("application/xml").body("""
            <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <QueueConfiguration><Queue>%s</Queue><Event>s3:ObjectRemoved:*</Event></QueueConfiguration>
            </NotificationConfiguration>
            """.formatted(queueArn))
            .when().put("/" + name + "?notification").then().statusCode(200);

        stack("UpdateStack", name, template.formatted(name, "second"));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_COMPLETE");

        bucketGet(name, "tagging").body(containsString("<Value>second</Value>"));
        bucketGet(name, "notification").body(containsString("<Event>s3:ObjectRemoved:*</Event>"));
    }

    /** An update that leaves the configuration as it was does not put it again, so no new test event. */
    @Test
    void anUpdateThatKeepsTheConfigurationSendsNoNewTestEvent() throws Exception {
        String name = "cfn-notify-same-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");
        drain(name);

        stack("UpdateStack", name, template(name, QUEUE_AND_FUNCTION
                + ", \"Tags\": [ { \"Key\": \"touched\", \"Value\": \"yes\" } ]"));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_COMPLETE");

        bucketGet(name, "tagging").body(containsString("<Key>touched</Key>"));
        assertEquals(List.of(), drain(name));
    }

    /**
     * Cloud Control reads a resource without an id as a failed create. A rejected destination
     * must reach it that way, with the bucket the attempt made deleted.
     */
    @Test
    void cloudControlReportsARejectedDestinationAsAFailedCreate() throws Exception {
        String name = "cc-notify-reject-" + Long.toString(System.nanoTime(), 36);
        String desiredState = """
            {"BucketName": "%1$s", "NotificationConfiguration": {"QueueConfigurations": [
              {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:%1$s-absent"}]}}
            """.formatted(name);
        String ct = "application/x-amz-json-1.0";
        String token = given()
            .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, ContentType.TEXT)))
            .contentType(ct)
            .header("X-Amz-Target", "CloudApiService.CreateResource")
            .body(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("TypeName", "AWS::S3::Bucket").put("DesiredState", desiredState)))
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("ProgressEvent.RequestToken");

        String status = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            status = given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, ContentType.TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                .body("{\"RequestToken\":\"" + token + "\"}")
            .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("ProgressEvent.OperationStatus");
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals("FAILED", status);
        given().when().head("/" + name).then().statusCode(404);
    }

    /** A declared but empty configuration is applied, which takes the stack's entries off the bucket. */
    @Test
    void anUpdateToAnEmptyConfigurationClearsTheBucket() throws Exception {
        String name = "cfn-notify-clear-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");
        bucketGet(name, "notification").body(containsString("<QueueConfiguration>"));

        stack("UpdateStack", name, template(name, ", \"NotificationConfiguration\": {}"));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_COMPLETE");

        bucketGet(name, "notification")
            .body(not(containsString("<QueueConfiguration>")))
            .body(not(containsString("<CloudFunctionConfiguration>")));
    }

    /** As on AWS, an update that removes the property takes the configuration off the bucket. */
    @Test
    void anUpdateThatRemovesTheConfigurationClearsTheBucket() throws Exception {
        String name = "cfn-notify-remove-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, QUEUE_AND_FUNCTION));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");
        bucketGet(name, "notification").body(containsString("<QueueConfiguration>"));

        stack("UpdateStack", name, template(name, ""));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_COMPLETE");

        bucketGet(name, "notification")
            .body(not(containsString("<QueueConfiguration>")))
            .body(not(containsString("<TopicConfiguration>")))
            .body(not(containsString("<CloudFunctionConfiguration>")));
    }

    /**
     * A configuration the stack never declared is left alone, so one put on the bucket outside the
     * stack survives an update, as it does on AWS. This passes with or without the property being
     * applied; it guards against treating an undeclared property as a removed one.
     */
    @Test
    void anUpdateThatDoesNotDeclareTheConfigurationLeavesTheBucketsOwn() throws Exception {
        String name = "cfn-notify-keep-" + Long.toString(System.nanoTime(), 36);
        String stackId = stack("CreateStack", name, template(name, ""));
        CfnStackWaits.awaitStatus(stackId, "CREATE_COMPLETE");

        String queueArn = "arn:aws:sqs:us-east-1:000000000000:" + name;
        given().contentType("application/xml").body("""
            <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <QueueConfiguration><Queue>%s</Queue><Event>s3:ObjectCreated:*</Event></QueueConfiguration>
            </NotificationConfiguration>
            """.formatted(queueArn))
            .when().put("/" + name + "?notification").then().statusCode(200);

        stack("UpdateStack", name, template(name, ", \"Tags\": [ { \"Key\": \"touched\", \"Value\": \"yes\" } ]"));
        CfnStackWaits.awaitStatus(stackId, "UPDATE_COMPLETE");

        bucketGet(name, "tagging").body(containsString("<Key>touched</Key>"));
        bucketGet(name, "notification").body(containsString("<Queue>" + queueArn + "</Queue>"));
    }

    private List<Message> drain(String queueName) {
        String queueUrl = sqsService.getQueueUrl(queueName, REGION);
        List<Message> all = new ArrayList<>();
        List<Message> batch;
        do {
            batch = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
            all.addAll(batch);
        } while (!batch.isEmpty());
        return all;
    }

    private static void putObject(String bucket, String key) {
        given().contentType("text/plain").body("payload")
            .when().put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static ValidatableResponse bucketGet(String bucket, String subresource) {
        return given().when().get("/" + bucket + "?" + subresource).then().statusCode(200);
    }

    private static String stack(String action, String stackName, String template) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        int start = xml.indexOf("<StackId>") + "<StackId>".length();
        return xml.substring(start, xml.indexOf("</StackId>", start));
    }
}
