package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.model.LogDestination;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudWatchLogsCrossAccountIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/logs/aws4_request";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    StorageFactory storageFactory;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void destinationAndAccountPolicyLifecycle() {
        json("Logs_20140328.PutDestination",
                "{\"destinationName\":\"cross-account\",\"targetArn\":\"arn:aws:kinesis:us-east-1:000000000000:stream/logs\",\"roleArn\":\"arn:aws:iam::000000000000:role/logs\"}")
                .then().statusCode(200).body("destination.destinationName", equalTo("cross-account"));
        json("Logs_20140328.PutDestinationPolicy",
                "{\"destinationName\":\"cross-account\",\"accessPolicy\":\"{}\"}")
                .then().statusCode(200);
        json("Logs_20140328.PutAccountPolicy",
                "{\"policyName\":\"central-subscription\",\"policyDocument\":\"{}\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\",\"scope\":\"ALL\"}")
                .then().statusCode(200).body("accountPolicy.policyName", equalTo("central-subscription"));
        json("Logs_20140328.DescribeAccountPolicies",
                "{\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\"}")
                .then().statusCode(200).body("accountPolicies", hasSize(1));
    }

    @Test
    void transformerPolicyAcceptsProcessorArrayDocument() {
        json("Logs_20140328.PutAccountPolicy",
                "{\"policyName\":\"account-transformer\",\"policyDocument\":\"[{\\\"parseJSON\\\":{}}]\","
                        + "\"policyType\":\"TRANSFORMER_POLICY\",\"scope\":\"ALL\"}")
                .then().statusCode(200)
                .body("accountPolicy.policyType", equalTo("TRANSFORMER_POLICY"))
                .body("accountPolicy.policyDocument", equalTo("[{\"parseJSON\":{}}]"));
    }

    @Test
    void destinationPolicyRequiresExistingDestination() {
        json("Logs_20140328.PutDestinationPolicy",
                "{\"destinationName\":\"missing\",\"accessPolicy\":\"{}\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void destinationCreationTagsCanBeListedByArn() {
        String name = "tagged-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = putTaggedDestination(name);

        json("Logs_20140328.ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("tags.team", equalTo("red"));
    }

    @Test
    void destinationTagsCanBeUpdatedAndRemoved() {
        String name = "updated-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = putTaggedDestination(name);

        json("Logs_20140328.TagResource", """
                {"resourceArn":"%s","tags":{"team":"blue","env":"dev"}}
                """.formatted(arn)).then().statusCode(200);
        json("Logs_20140328.ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("tags.team", equalTo("blue")).body("tags.env", equalTo("dev"));

        json("Logs_20140328.UntagResource", "{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"team\"]}")
                .then().statusCode(200);
        json("Logs_20140328.ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("tags.team", nullValue()).body("tags.env", equalTo("dev"));
    }

    @Test
    void destinationTagsDoNotCrossAccountRegionOrPartition() {
        String name = "isolated-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = putTaggedDestination(name);

        for (String foreignArn : new String[]{
                arn.replace(":000000000000:", ":111111111111:"),
                arn.replace(":us-east-1:", ":eu-west-1:"),
                arn.replace("arn:aws:", "arn:aws-cn:")}) {
            json("Logs_20140328.ListTagsForResource", "{\"resourceArn\":\"" + foreignArn + "\"}")
                    .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
            json("Logs_20140328.TagResource", """
                    {"resourceArn":"%s","tags":{"team":"foreign"}}
                    """.formatted(foreignArn)).then().statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundException"));
        }
        json("Logs_20140328.ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("tags.team", equalTo("red"));
    }

    @Test
    void destinationTagsRoundTripThroughAwsSdk() {
        String name = "sdk-tagged-" + UUID.randomUUID().toString().substring(0, 8);
        registerDestinationCleanup(name);
        try (CloudWatchLogsClient client = AwsTestClients.create(
                CloudWatchLogsClient.builder(), "AKID", Region.US_EAST_1)) {
            String arn = client.putDestination(request -> request.destinationName(name)
                    .targetArn("arn:aws:kinesis:us-east-1:000000000000:stream/logs")
                    .roleArn("arn:aws:iam::000000000000:role/logs")
                    .tags(Map.of("team", "red"))).destination().arn();
            assertEquals(Map.of("team", "red"),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());

            client.tagResource(request -> request.resourceArn(arn).tags(Map.of("team", "blue")));
            assertEquals(Map.of("team", "blue"),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());

            client.untagResource(request -> request.resourceArn(arn).tagKeys("team"));
            assertEquals(Map.of(),
                    client.listTagsForResource(request -> request.resourceArn(arn)).tags());
        }
    }

    private String putTaggedDestination(String name) {
        registerDestinationCleanup(name);
        json("Logs_20140328.PutDestination", """
                {"destinationName":"%s","targetArn":"arn:aws:kinesis:us-east-1:000000000000:stream/logs",
                 "roleArn":"arn:aws:iam::000000000000:role/logs","tags":{"team":"red"}}
                """.formatted(name)).then().statusCode(200);
        return "arn:aws:logs:us-east-1:000000000000:destination:" + name;
    }

    private void registerDestinationCleanup(String name) {
        StorageBackend<String, LogDestination> destinations = storageFactory.create("cloudwatchlogs",
                "cwlogs-destinations.json", new TypeReference<Map<String, LogDestination>>() {});
        cleanup.register(() -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> destinations.delete(REGION + "::" + name)));
    }

    private static Response json(String target, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH).header("X-Amz-Target", target)
                .body(body).post("/");
    }
}
