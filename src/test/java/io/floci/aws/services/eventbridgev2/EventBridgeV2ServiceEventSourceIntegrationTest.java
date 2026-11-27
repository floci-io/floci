package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DescribeRuleResponse;
import software.amazon.awssdk.services.eventbridge.model.ManagedRuleException;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.CreateEventSourceResponse;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceConfiguration;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidInputException;
import software.amazon.awssdk.services.eventbridgev2.model.TransformerType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An AWS service event source forwards the service's events to its bus through a managed rule, through the SDKs. */
@QuarkusTest
class EventBridgeV2ServiceEventSourceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/EventBusDelivery";

    @Test
    void aServiceSourceDeliversTheServicesEventsToASubscriber() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        try (EventBridgeV2Client events = AwsTestClients.create(EventBridgeV2Client.builder(), ACCOUNT, Region.US_EAST_1);
             EventBridgeClient rules = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
             SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1);
             SsmClient ssm = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String queueUrl = sqs.createQueue(request -> request.queueName("v2-svc-" + suffix)).queueUrl();
            String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String bus = events.createEventBus(request -> request.name("v2-svc-" + suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("all").eventBusArn(bus)
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn).roleArn(ROLE))
                    .transformer(transformer -> transformer.type(TransformerType.WITH_METADATA))).subscriberArn();

            CreateEventSourceResponse created = events.createEventSource(request -> request.name("ssm-" + suffix)
                    .eventBusArn(bus).configuration(EventSourceConfiguration.fromAwsServiceEventsConfiguration(
                            service -> service.awsService("aws.ssm")
                                    .pattern("{\"detail-type\":[\"Parameter Store Change\"]}"))));
            String sourceArn = created.eventSourceArn();
            assertTrue(sourceArn.contains(":event-sourcev2/aws.service/ssm-" + suffix + "/"), sourceArn);
            assertEquals("ACTIVE", created.stateAsString());
            assertNull(created.eventSourceDetails());
            String ruleName = sourceArn.substring(sourceArn.length() - 25);

            DescribeRuleResponse rule = rules.describeRule(request -> request.name(ruleName));
            assertEquals("events.amazonaws.com", rule.managedBy());
            assertEquals("{\"source\":[\"aws.ssm\"],\"account\":[\"" + ACCOUNT + "\"],\"region\":[\"us-east-1\"],"
                    + "\"detail-type\":[\"Parameter Store Change\"]}", rule.eventPattern());
            assertEquals(bus, rules.listTargetsByRule(request -> request.rule(ruleName)).targets().getFirst().arn());
            assertEquals(ruleName, rules.listRuleNamesByTarget(request -> request.targetArn(bus)).ruleNames()
                    .getFirst());
            assertThrows(ManagedRuleException.class, () -> rules.deleteRule(request -> request.name(ruleName)));

            ssm.putParameter(request -> request.name("/svc-" + suffix).value("v").type(ParameterType.STRING));

            List<Message> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                received.addAll(sqs.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(10)
                        .waitTimeSeconds(1)).messages());
                assertEquals(1, received.size(), received.toString());
            });
            JsonNode body = MAPPER.readTree(received.getFirst().body());
            JsonNode data = body.path("Data");
            assertEquals("aws.ssm", data.path("source").asText());
            assertEquals("Parameter Store Change", data.path("detail-type").asText());
            assertTrue(body.path("Metadata").isObject() && body.path("Metadata").isEmpty(), body.toString());
            JsonNode system = body.path("SystemMetadata");
            List<String> keys = new ArrayList<>();
            system.fieldNames().forEachRemaining(keys::add);
            assertEquals(List.of("EventGroupId", "DeduplicationId", "ContentType", "aws:IngestionTime", "aws:Source",
                    "aws:DetailType", "aws:EventId", "aws:SequenceNumber", "aws:DeliveryType"), keys);
            assertEquals(data.path("id").asText(), system.path("EventGroupId").asText());
            assertEquals(data.path("id").asText(), system.path("DeduplicationId").asText());
            assertEquals("application/eventbridge+json", system.path("ContentType").asText());
            assertTrue(system.path("aws:IngestionTime").asText().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"),
                    system.toString());
            assertEquals("aws.ssm", system.path("aws:Source").asText());
            assertEquals("Parameter Store Change", system.path("aws:DetailType").asText());
            assertEquals("LIVE", system.path("aws:DeliveryType").asText());

            events.deleteEventSource(request -> request.eventSourceArn(sourceArn));
            assertThrows(ResourceNotFoundException.class,
                    () -> rules.describeRule(request -> request.name(ruleName)));
            events.deleteSubscriber(request -> request.subscriberArn(subscriber));
            events.deleteEventBus(request -> request.eventBusArn(bus));
            ssm.deleteParameter(request -> request.name("/svc-" + suffix));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }

    @Test
    void aPartnerSourceFailsAsAMissingPartnerSource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        try (EventBridgeV2Client events = AwsTestClients.create(EventBridgeV2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            String bus = events.createEventBus(request -> request.name("v2-partner-" + suffix)).eventBusArn();

            InvalidInputException e = assertThrows(InvalidInputException.class, () -> events.createEventSource(
                    request -> request.name("partner-" + suffix).eventBusArn(bus)
                            .configuration(EventSourceConfiguration.fromPartnerEventsConfiguration(partner -> partner
                                    .partnerEventSourceArn("arn:aws:events:us-east-1:111111111111:event-source/"
                                            + "aws.partner/example.com/123/orders")))));

            assertEquals("The partner event source aws.partner/example.com/123/orders could not be described: Event "
                    + "source aws.partner/example.com/123/orders does not exist.", e.awsErrorDetails().errorMessage());
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }
}
