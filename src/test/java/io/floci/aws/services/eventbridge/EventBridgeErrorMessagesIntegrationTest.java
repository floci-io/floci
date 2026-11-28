package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;

/** Exact code, message and status of the not-found, already-exists and invalid-state refusals. */
@QuarkusTest
class EventBridgeErrorMessagesIntegrationTest {

    private static final String BUS_ARN = "arn:aws:events:us-east-1:000000000000:event-bus/";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String op, String body) {
        return RestAssuredJsonUtils.awsAction("AWSEvents", op, body).then();
    }

    private static void ok(String op, String body) {
        call(op, body).statusCode(200);
    }

    private static void refused(String op, String body, String type, String message) {
        call(op, body).statusCode(400).body("__type", equalTo(type)).body("message", equalTo(message));
    }

    private static void createBus(String name) {
        ok("CreateEventBus", "{\"Name\":\"" + name + "\"}");
    }

    private static void createArchive(String name, String bus) {
        ok("CreateArchive", "{\"ArchiveName\":\"" + name + "\",\"EventSourceArn\":\"" + BUS_ARN + bus + "\"}");
    }

    @Test
    void notFoundIsWordedPerResourceKind() {
        String nf = "ResourceNotFoundException";
        createBus("emsg-bus");

        refused("DescribeEventBus", "{\"Name\":\"emsg-nobus\"}", nf, "Event bus emsg-nobus does not exist.");
        refused("DescribeRule", "{\"Name\":\"emsg-norule\"}", nf,
                "Rule emsg-norule does not exist on EventBus default.");
        refused("DescribeRule", "{\"Name\":\"emsg-norule\",\"EventBusName\":\"emsg-bus\"}", nf,
                "Rule emsg-norule does not exist on EventBus emsg-bus.");
        refused("EnableRule", "{\"Name\":\"emsg-norule\"}", nf,
                "Rule emsg-norule does not exist on EventBus default.");
        refused("PutTargets", "{\"Rule\":\"emsg-norule\",\"Targets\":[{\"Id\":\"1\","
                + "\"Arn\":\"arn:aws:sqs:us-east-1:000000000000:q\"}]}", nf,
                "Rule emsg-norule does not exist on EventBus default.");
        refused("DescribeArchive", "{\"ArchiveName\":\"emsg-noarch\"}", nf,
                "Archive emsg-noarch does not exist.");
        refused("DescribeReplay", "{\"ReplayName\":\"emsg-noreplay\"}", nf,
                "Replay emsg-noreplay does not exist.");
        refused("DescribeConnection", "{\"Name\":\"emsg-noconn\"}", nf,
                "Failed to describe the connection(s). Connection 'emsg-noconn' does not exist.");
        refused("DescribeApiDestination", "{\"Name\":\"emsg-nodest\"}", nf,
                "Failed to describe the api-destination(s). An api-destination 'emsg-nodest' does not exist.");
        String ghost = "arn:aws:events:us-east-1:000000000000:connection/emsg-ghost/"
                + "00000000-0000-0000-0000-000000000000";
        refused("CreateApiDestination", "{\"Name\":\"emsg-d\",\"ConnectionArn\":\"" + ghost + "\","
                + "\"InvocationEndpoint\":\"https://example.com\",\"HttpMethod\":\"POST\"}", nf,
                "Failed to create the api-destination(s). Connection '" + ghost + "' does not exist.");
        refused("TagResource", "{\"ResourceARN\":\"" + BUS_ARN + "emsg-nobus\",\"Tags\":[]}", nf,
                "Event bus emsg-nobus does not exist.");
    }

    @Test
    void deletedResourcesReadBackAsNotFound() {
        createBus("emsg-del-bus");
        ok("DeleteEventBus", "{\"Name\":\"emsg-del-bus\"}");
        refused("DescribeEventBus", "{\"Name\":\"emsg-del-bus\"}", "ResourceNotFoundException",
                "Event bus emsg-del-bus does not exist.");

        createArchive("emsg-del-arch", "default");
        ok("DeleteArchive", "{\"ArchiveName\":\"emsg-del-arch\"}");
        refused("DescribeArchive", "{\"ArchiveName\":\"emsg-del-arch\"}", "ResourceNotFoundException",
                "Archive emsg-del-arch does not exist.");

        ok("PutRule", "{\"Name\":\"emsg-del-rule\",\"EventPattern\":\"{\\\"source\\\":[\\\"x\\\"]}\"}");
        ok("DeleteRule", "{\"Name\":\"emsg-del-rule\"}");
        refused("DescribeRule", "{\"Name\":\"emsg-del-rule\"}", "ResourceNotFoundException",
                "Rule emsg-del-rule does not exist on EventBus default.");
    }

    @Test
    void duplicateCreatesAreAlreadyExists() {
        String ae = "ResourceAlreadyExistsException";
        createBus("emsg-dup-bus");
        refused("CreateEventBus", "{\"Name\":\"emsg-dup-bus\"}", ae, "Event bus emsg-dup-bus already exists.");

        createArchive("emsg-dup-arch", "default");
        refused("CreateArchive", "{\"ArchiveName\":\"emsg-dup-arch\",\"EventSourceArn\":\"" + BUS_ARN
                + "default\"}", ae, "Archive emsg-dup-arch already exists.");

        String conn = "{\"Name\":\"emsg-dup-conn\",\"AuthorizationType\":\"API_KEY\",\"AuthParameters\":"
                + "{\"ApiKeyAuthParameters\":{\"ApiKeyName\":\"k\",\"ApiKeyValue\":\"v\"}}}";
        ok("CreateConnection", conn);
        refused("CreateConnection", conn, ae,
                "Connection emsg-dup-conn already exists.");

        String replay = "{\"ReplayName\":\"emsg-dup-replay\",\"EventSourceArn\":\"arn:aws:events:us-east-1:"
                + "000000000000:archive/emsg-dup-arch\",\"EventStartTime\":1700000000,\"EventEndTime\":1700000100,"
                + "\"Destination\":{\"Arn\":\"" + BUS_ARN + "default\"}}";
        ok("StartReplay", replay);
        refused("StartReplay", replay, ae, "Replay emsg-dup-replay already exists.");
    }

    @Test
    void invalidStateAndPreconditionRefusals() {
        refused("DeleteEventBus", "{\"Name\":\"default\"}", "ValidationException",
                "Cannot delete event bus default.");

        createBus("emsg-rules-bus");
        ok("PutRule", "{\"Name\":\"r1\",\"EventBusName\":\"emsg-rules-bus\","
                + "\"EventPattern\":\"{\\\"source\\\":[\\\"x\\\"]}\"}");
        refused("DeleteEventBus", "{\"Name\":\"emsg-rules-bus\"}", "ValidationException",
                "EventBus can't be deleted since it has rules.");

        ok("PutTargets", "{\"Rule\":\"r1\",\"EventBusName\":\"emsg-rules-bus\",\"Targets\":[{\"Id\":\"1\","
                + "\"Arn\":\"arn:aws:sqs:us-east-1:000000000000:q\"}]}");
        refused("DeleteRule", "{\"Name\":\"r1\",\"EventBusName\":\"emsg-rules-bus\"}", "ValidationException",
                "Rule can't be deleted since it has targets.");

        refused("StartReplay", "{\"ReplayName\":\"emsg-bad-src\",\"EventSourceArn\":\"arn:aws:events:us-east-1:"
                + "000000000000:archive/emsg-gone\",\"EventStartTime\":1700000000,\"EventEndTime\":1700000100,"
                + "\"Destination\":{\"Arn\":\"" + BUS_ARN + "default\"}}", "ValidationException",
                "Parameter EventSourceArn is not valid. Reason: Archive emsg-gone does not exist.");
    }

    @Test
    void connectionAndApiDestinationWritesOnMissingResourcesNameTheCall() {
        String nf = "ResourceNotFoundException";
        refused("UpdateConnection", "{\"Name\":\"emsg-w-conn\",\"Description\":\"d\"}", nf,
                "Failed to update the connection(s). Connection 'emsg-w-conn' does not exist.");
        refused("DeleteConnection", "{\"Name\":\"emsg-w-conn\"}", nf,
                "Connection emsg-w-conn does not exist.");
        refused("UpdateApiDestination", "{\"Name\":\"emsg-w-dest\",\"Description\":\"d\"}", nf,
                "Failed to update the api-destination(s). An api-destination 'emsg-w-dest' does not exist.");
        refused("DeleteApiDestination", "{\"Name\":\"emsg-w-dest\"}", nf,
                "ApiDestination emsg-w-dest does not exist.");
    }

    @Test
    void untagOnMissingRuleArnNamesRuleAndBus() {
        refused("UntagResource", "{\"ResourceARN\":\"arn:aws:events:us-east-1:000000000000:rule/emsg-untag\","
                + "\"TagKeys\":[\"k\"]}", "ResourceNotFoundException",
                "Rule emsg-untag does not exist on EventBus default.");
    }

    @Test
    void tagCallsOnMissingResourcesUseThePerKindWording() {
        String nf = "ResourceNotFoundException";
        String events = "arn:aws:events:us-east-1:000000000000:";
        refused("ListTagsForResource", "{\"ResourceARN\":\"" + events + "archive/emsg-tag-arch\"}", nf,
                "Resource not found: " + events + "archive/emsg-tag-arch");
        refused("TagResource", "{\"ResourceARN\":\"" + events + "rule/emsg-tag-bus/emsg-tag-rule\","
                + "\"Tags\":[]}", nf, "Rule emsg-tag-rule does not exist on EventBus emsg-tag-bus.");
        refused("UntagResource", "{\"ResourceARN\":\"" + events + "event-bus/emsg-tag-bus\","
                + "\"TagKeys\":[\"k\"]}", nf, "Event bus emsg-tag-bus does not exist.");
    }

    @Test
    void listRuleNamesByTargetOnMissingBusUsesTheBusWording() {
        refused("ListRuleNamesByTarget", "{\"TargetArn\":\"arn:aws:sqs:us-east-1:000000000000:q\","
                + "\"EventBusName\":\"emsg-lrnbt-bus\"}", "ResourceNotFoundException",
                "Event bus emsg-lrnbt-bus does not exist.");
    }
}
