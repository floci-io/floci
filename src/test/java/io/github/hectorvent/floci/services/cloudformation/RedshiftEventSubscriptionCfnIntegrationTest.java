package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.EventSubscription;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RedshiftEventSubscriptionCfnIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261026/us-east-1/cloudformation/aws4_request";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"CommaDelimitedList", "List<String>"})
    void listParametersPopulateSubscriptionFilters(String parameterType) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stack = "redshift-list-parameters-" + suffix;
        String name = "redshift-list-watcher-" + suffix;
        String group = "redshift-list-group-" + suffix;
        String body = template(name, true, true)
                .replace("\"Resources\": {", """
                        "Parameters": {
                          "Categories": {"Type":"%s"},
                          "Sources": {"Type":"%s"}
                        },
                        "Resources": {
                        """.formatted(parameterType, parameterType))
                .replace("\"SourceType\":\"cluster\"",
                        "\"SourceType\":\"cluster-parameter-group\",\"SourceIds\":{\"Ref\":\"Sources\"}")
                .replace("\"EventCategories\":[\"management\"]", "\"EventCategories\":{\"Ref\":\"Categories\"}");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
                RedshiftClient redshift = RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build()) {
            redshift.createClusterParameterGroup(request -> request.parameterGroupName(group)
                    .parameterGroupFamily("redshift-1.0").description("list parameter source"));
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(body).parameters(
                        parameter -> parameter.parameterKey("Categories").parameterValue("configuration,management"),
                        parameter -> parameter.parameterKey("Sources").parameterValue(group)));
                try {
                    assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                    EventSubscription created = subscription(redshift, name);
                    assertEquals(List.of("configuration", "management"), created.eventCategoriesList());
                    assertEquals(List.of(group), created.sourceIdsList());
                } finally {
                    cfn.deleteStack(request -> request.stackName(stack));
                    CfnStackWaits.awaitStackDeleted(stack);
                }
            } finally {
                redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(group));
            }
        }
    }

    @Test
    void rollbackRestoresSubscriptionWhoseSourceWasDeletedAfterCreation() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stack = "redshift-events-rollback-" + suffix;
        String name = "redshift-rollback-watcher-" + suffix;
        String group = "redshift-rollback-group-" + suffix;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
                RedshiftClient redshift = RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build()) {
            redshift.createClusterParameterGroup(request -> request.parameterGroupName(group)
                    .parameterGroupFamily("redshift-1.0").description("rollback source"));
            String initial = template(name, true, true).replace("\"SourceType\":\"cluster\"",
                    "\"SourceType\":\"cluster-parameter-group\",\"SourceIds\":[\"" + group + "\"]")
                    .replace("\"EventCategories\":[\"management\"]", "\"EventCategories\":[\"configuration\"]");
            cfn.createStack(request -> request.stackName(stack).templateBody(initial));
            try {
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                EventSubscription prior = subscription(redshift, name);
                redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(group));
                String failingUpdate = template(name, false, false).replace("\"Resources\": {", """
                        "Resources": {
                          "Broken": {
                            "Type":"AWS::SQS::Queue", "DependsOn":"Watcher",
                            "Properties":{"QueueName":"invalid-fifo","FifoQueue":true}
                          },
                        """);

                cfn.updateStack(request -> request.stackName(stack).templateBody(failingUpdate));

                assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                EventSubscription restored = subscription(redshift, name);
                assertEquals(prior.sourceIdsList(), restored.sourceIdsList());
                assertEquals(prior.sourceType(), restored.sourceType());
                assertEquals(prior.eventCategoriesList(), restored.eventCategoriesList());
                assertEquals(prior.severity(), restored.severity());
                assertEquals(prior.enabled(), restored.enabled());
                assertEquals(prior.tags(), restored.tags());
                assertEquals(prior.status(), restored.status());
                assertEquals(prior.subscriptionCreationTime(), restored.subscriptionCreationTime());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void stackCreatesUpdatesReplacesAndDeletesSubscriptionWithExactGetAttValues() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stack = "redshift-events-cfn-" + suffix;
        String name = "redshift-watcher-" + suffix;
        String replacement = "redshift-renamed-" + suffix;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
                RedshiftClient redshift = RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build()) {
            cfn.createStack(request -> request.stackName(stack).templateBody(template(name, true, true)));
            try {
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                EventSubscription created = subscription(redshift, name);
                Map<String, String> outputs = cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst()
                        .outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
                assertEquals(name, outputs.get("Ref"));
                assertEquals(name, outputs.get("CustSubscriptionId"));
                assertEquals(created.customerAwsId(), outputs.get("CustomerAwsId"));
                assertEquals("active", outputs.get("Status"));
                assertEquals(created.subscriptionCreationTime(), Instant.parse(outputs.get("SubscriptionCreationTime")));
                assertEquals("", outputs.get("SourceIdsList"));
                assertEquals("management", outputs.get("EventCategoriesList"));
                assertEquals("initial", created.tags().getFirst().value());

                cfn.updateStack(request -> request.stackName(stack).templateBody(template(name, false, false)));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                EventSubscription updated = subscription(redshift, name);
                assertFalse(updated.enabled());
                assertNull(updated.sourceType());
                assertNull(updated.severity());
                assertTrue(updated.eventCategoriesList().isEmpty());
                assertTrue(updated.tags().isEmpty());
                assertEquals(created.subscriptionCreationTime(), updated.subscriptionCreationTime());

                cfn.updateStack(request -> request.stackName(stack).templateBody(template(replacement, true, true)));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                assertEquals(replacement, subscription(redshift, replacement).custSubscriptionId());
                assertThrows(RedshiftException.class, () -> subscription(redshift, name));
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
            assertThrows(RedshiftException.class, () -> subscription(redshift, replacement));
        }
    }

    private static EventSubscription subscription(RedshiftClient redshift, String name) {
        return redshift.describeEventSubscriptions(request -> request.subscriptionName(name)).eventSubscriptionsList().getFirst();
    }

    private static String template(String name, boolean enabled, boolean filtered) {
        String filters = filtered ? """
                ,"SourceType":"cluster","Severity":"INFO","EventCategories":["management"],
                "Tags":[{"Key":"phase","Value":"initial"}]
                """ : "";
        return """
                {
                  "Resources": {
                    "Topic": {"Type":"AWS::SNS::Topic"},
                    "Watcher": {
                      "Type":"AWS::Redshift::EventSubscription",
                      "Properties": {
                        "SubscriptionName":"%s", "SnsTopicArn":{"Ref":"Topic"}, "Enabled":%s%s
                      }
                    }
                  },
                  "Outputs": {
                    "Ref":{"Value":{"Ref":"Watcher"}},
                    "CustomerAwsId":{"Value":{"Fn::GetAtt":["Watcher","CustomerAwsId"]}},
                    "CustSubscriptionId":{"Value":{"Fn::GetAtt":["Watcher","CustSubscriptionId"]}},
                    "Status":{"Value":{"Fn::GetAtt":["Watcher","Status"]}},
                    "SubscriptionCreationTime":{"Value":{"Fn::GetAtt":["Watcher","SubscriptionCreationTime"]}},
                    "SourceIdsList":{"Value":{"Fn::Join":[",",{"Fn::GetAtt":["Watcher","SourceIdsList"]}]}},
                    "EventCategoriesList":{"Value":{"Fn::Join":[",",{"Fn::GetAtt":["Watcher","EventCategoriesList"]}]}}
                  }
                }
                """.formatted(name, enabled, filters);
    }
}
