package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::Events::Rule}'s {@code EventPattern} may be written as a JSON object or as a
 * string that holds one; CloudFormation accepts both. Either way the rule is created with the
 * pattern PutRule would store, and it matches the events that pattern describes.
 */
@QuarkusTest
class CloudFormationEventsRulePatternIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @ParameterizedTest(name = "a pattern written as {0}")
    @CsvSource(delimiter = '|', value = {
            "an object | object | {\"source\": [\"cfn.rule.pattern.object\"], \"detail-type\": [\"Order Placed\"]}",
            "a string  | string | \"{ \\\"source\\\": [ \\\"cfn.rule.pattern.string\\\" ], \\\"detail-type\\\": [ \\\"Order Placed\\\" ]}\""})
    void theRuleIsCreatedAndMatchesTheEventsItsPatternDescribes(String label, String form, String pattern) {
        String name = "cfn-rule-pattern-" + form;
        String source = "cfn.rule.pattern." + form;
        String template = """
                {"Resources": {
                  "Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%1$s"}},
                  "Rule": {"Type": "AWS::Events::Rule", "Properties": {
                    "Name": "%1$s",
                    "EventPattern": %2$s,
                    "Targets": [{"Id": "Queue", "Arn": {"Fn::GetAtt": ["Queue", "Arn"]}}]}}}}
                """.formatted(name, pattern);
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
             EventBridgeClient events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
             SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            cfn.createStack(request -> request.stackName(name).templateBody(template));
            try {
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status(),
                        () -> CfnStackWaits.failedResourceReason(name));

                JsonPath stored = new JsonPath(events.describeRule(request -> request.name(name)).eventPattern());
                assertEquals(List.of(source), stored.getList("source"));
                assertEquals(List.of("Order Placed"), stored.getList("detail-type"));

                assertEquals(0, events.putEvents(request -> request.entries(
                        entry(source, "Order Placed", "o-1"),
                        entry(source, "Order Shipped", "o-2"))).failedEntryCount());

                String queueUrl = sqs.getQueueUrl(request -> request.queueName(name)).queueUrl();
                List<Message> received = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                        .maxNumberOfMessages(10)).messages();
                assertEquals(1, received.size());
                assertEquals("o-1", new JsonPath(received.getFirst().body()).getString("detail.orderId"));
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
            }
        }
    }

    private static PutEventsRequestEntry entry(String source, String detailType, String orderId) {
        return PutEventsRequestEntry.builder()
                .source(source)
                .detailType(detailType)
                .detail("{\"orderId\":\"" + orderId + "\"}")
                .build();
    }
}
