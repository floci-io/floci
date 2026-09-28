package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.GetApiKeyResponse;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionResponse;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.ResourceTagMapping;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.UntagResourcesResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Resource Groups Tagging discovery of resources tagged by their own service")
class ResourceGroupsTaggingDiscoveryTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static ResourceGroupsTaggingApiClient tagging;
    private static SqsClient sqs;
    private static LambdaClient lambda;
    private static CloudWatchLogsClient logs;
    private static ApiGatewayClient apiGateway;

    @BeforeAll
    static void setup() {
        tagging = TestFixtures.resourceGroupsTaggingApiClient();
        sqs = TestFixtures.sqsClient();
        lambda = TestFixtures.lambdaClient();
        logs = TestFixtures.cloudWatchLogsClient();
        apiGateway = TestFixtures.apiGatewayClient();
    }

    @AfterAll
    static void cleanup() {
        if (tagging != null) {
            tagging.close();
            sqs.close();
            lambda.close();
            logs.close();
            apiGateway.close();
        }
    }

    @Test
    @DisplayName("SQS queues tagged on CreateQueue are found by type and tag filters, page by page, and stay usable")
    void sqsQueueIsDiscoveredAndUsable() {
        String run = TestFixtures.uniqueName("run");
        String primaryName = TestFixtures.uniqueName("tag-discovery-primary");
        String secondaryName = TestFixtures.uniqueName("tag-discovery-secondary");
        String primaryUrl = sqs.createQueue(request -> request
                .queueName(primaryName)
                .tags(Map.of("Run", run, "Team", "platform", "Env", "dev"))).queueUrl();
        String secondaryUrl = sqs.createQueue(request -> request
                .queueName(secondaryName)
                .tags(Map.of("Run", run))).queueUrl();

        try {
            GetResourcesResponse matched = tagging.getResources(request -> request
                    .resourceTypeFilters("sqs:queue")
                    .tagFilters(tagFilter("Run", run), tagFilter("Team", "platform"), tagFilter("Env", "dev")));

            assertThat(matched.resourceTagMappingList()).singleElement().satisfies(mapping -> {
                assertThat(mapping.resourceARN()).endsWith(":" + primaryName);
                assertThat(tags(mapping)).containsOnly(
                        Map.entry("Run", run), Map.entry("Team", "platform"), Map.entry("Env", "dev"));
            });

            List<GetResourcesResponse> pages = tagging.getResourcesPaginator(GetResourcesRequest.builder()
                    .resourceTypeFilters("sqs:queue")
                    .tagFilters(tagFilter("Run", run))
                    .resourcesPerPage(1)
                    .build()).stream().collect(Collectors.toList());
            List<String> pagedArns = new ArrayList<>();
            for (GetResourcesResponse page : pages) {
                assertThat(page.resourceTagMappingList()).hasSizeLessThanOrEqualTo(1);
                page.resourceTagMappingList().forEach(mapping -> pagedArns.add(mapping.resourceARN()));
            }
            assertThat(pagedArns).hasSize(2)
                    .anySatisfy(arn -> assertThat(arn).endsWith(":" + primaryName))
                    .anySatisfy(arn -> assertThat(arn).endsWith(":" + secondaryName));

            String discoveredArn = matched.resourceTagMappingList().get(0).resourceARN();
            String discoveredName = discoveredArn.substring(discoveredArn.lastIndexOf(':') + 1);
            String discoveredUrl = sqs.getQueueUrl(request -> request.queueName(discoveredName)).queueUrl();
            SendMessageResponse sent = sqs.sendMessage(request -> request
                    .queueUrl(discoveredUrl)
                    .messageBody("found by tag"));

            assertThat(discoveredUrl).isEqualTo(primaryUrl);
            assertThat(sent.messageId()).isNotBlank();
        } finally {
            sqs.deleteQueue(request -> request.queueUrl(primaryUrl));
            sqs.deleteQueue(request -> request.queueUrl(secondaryUrl));
        }
    }

    @Test
    @DisplayName("Lambda functions tagged on CreateFunction are found by type and tag, and the ARN resolves")
    void lambdaFunctionIsDiscoveredByTag() {
        String run = TestFixtures.uniqueName("run");
        String functionName = TestFixtures.uniqueName("tag-discovery-fn");
        CreateFunctionResponse created = lambda.createFunction(request -> request
                .functionName(functionName)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .tags(Map.of("Run", run)));

        try {
            GetResourcesResponse matched = tagging.getResources(request -> request
                    .resourceTypeFilters("lambda:function")
                    .tagFilters(tagFilter("Run", run)));

            assertThat(matched.resourceTagMappingList()).singleElement().satisfies(mapping -> {
                assertThat(mapping.resourceARN()).isEqualTo(created.functionArn());
                assertThat(tags(mapping)).containsEntry("Run", run);
            });

            String discoveredArn = matched.resourceTagMappingList().get(0).resourceARN();
            GetFunctionResponse function = lambda.getFunction(request -> request.functionName(discoveredArn));

            assertThat(function.configuration().functionName()).isEqualTo(functionName);
            assertThat(function.tags()).containsEntry("Run", run);
        } finally {
            lambda.deleteFunction(request -> request.functionName(functionName));
        }
    }

    @Test
    @DisplayName("Log groups tagged on CreateLogGroup are found by type and tag, without the trailing :* ARN suffix")
    void logGroupIsDiscoveredByTag() {
        String run = TestFixtures.uniqueName("run");
        String logGroupName = "/compat/" + TestFixtures.uniqueName("tag-discovery");
        logs.createLogGroup(request -> request
                .logGroupName(logGroupName)
                .tags(Map.of("Run", run)));

        try {
            GetResourcesResponse matched = tagging.getResources(request -> request
                    .resourceTypeFilters("logs:log-group")
                    .tagFilters(tagFilter("Run", run)));

            assertThat(matched.resourceTagMappingList()).singleElement().satisfies(mapping -> {
                assertThat(mapping.resourceARN())
                        .endsWith(":log-group:" + logGroupName)
                        .doesNotEndWith(":*");
                assertThat(tags(mapping)).containsEntry("Run", run);
            });

            String discoveredArn = matched.resourceTagMappingList().get(0).resourceARN();
            String discoveredName = discoveredArn.substring(discoveredArn.indexOf("log-group:") + "log-group:".length());
            DescribeLogGroupsResponse described = logs.describeLogGroups(request -> request
                    .logGroupNamePrefix(discoveredName));

            assertThat(described.logGroups()).extracting(LogGroup::logGroupName).containsExactly(logGroupName);
        } finally {
            logs.deleteLogGroup(request -> request.logGroupName(logGroupName));
        }
    }

    @Test
    @DisplayName("API keys are found by type, tagged and untagged through the tagging API, and gone once deleted")
    void apiKeyIsDiscoveredAndTaggedThroughTaggingApi() {
        String run = TestFixtures.uniqueName("run");
        CreateApiKeyResponse created = apiGateway.createApiKey(request -> request
                .name(TestFixtures.uniqueName("tag-discovery-key"))
                .enabled(true)
                .tags(Map.of("Run", run, "Stage", "initial")));
        boolean deleted = false;

        try {
            GetResourcesResponse matched = tagging.getResources(request -> request
                    .resourceTypeFilters("apigateway:apikeys")
                    .tagFilters(tagFilter("Run", run)));

            assertThat(matched.resourceTagMappingList()).singleElement().satisfies(mapping -> {
                assertThat(mapping.resourceARN()).endsWith("::/apikeys/" + created.id());
                assertThat(tags(mapping)).containsOnly(Map.entry("Run", run), Map.entry("Stage", "initial"));
            });
            String arn = matched.resourceTagMappingList().get(0).resourceARN();

            TagResourcesResponse tagged = tagging.tagResources(request -> request
                    .resourceARNList(arn)
                    .tags(Map.of("Owner", "compat")));
            UntagResourcesResponse untagged = tagging.untagResources(request -> request
                    .resourceARNList(arn)
                    .tagKeys("Stage"));

            assertThat(tagged.failedResourcesMap()).isEmpty();
            assertThat(untagged.failedResourcesMap()).isEmpty();

            GetApiKeyResponse read = apiGateway.getApiKey(request -> request.apiKey(created.id()));
            assertThat(read.tags()).containsOnly(Map.entry("Run", run), Map.entry("Owner", "compat"));

            GetResourcesResponse retagged = tagging.getResources(request -> request
                    .resourceTypeFilters("apigateway:apikeys")
                    .tagFilters(tagFilter("Run", run), tagFilter("Owner", "compat")));
            assertThat(retagged.resourceTagMappingList()).singleElement().satisfies(mapping -> {
                assertThat(mapping.resourceARN()).isEqualTo(arn);
                assertThat(tags(mapping)).doesNotContainKey("Stage");
            });

            apiGateway.deleteApiKey(request -> request.apiKey(created.id()));
            deleted = true;

            GetResourcesResponse afterDelete = tagging.getResources(request -> request
                    .resourceTypeFilters("apigateway:apikeys")
                    .tagFilters(tagFilter("Run", run)));
            assertThat(afterDelete.resourceTagMappingList()).isEmpty();
        } finally {
            if (!deleted) {
                deleteApiKeyAfterFailure(created.id());
            }
        }
    }

    private static TagFilter tagFilter(String key, String value) {
        return TagFilter.builder().key(key).values(value).build();
    }

    private static Map<String, String> tags(ResourceTagMapping mapping) {
        return mapping.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static void deleteApiKeyAfterFailure(String id) {
        try {
            apiGateway.deleteApiKey(request -> request.apiKey(id));
        } catch (SdkException ignored) {
            // Runs only after an assertion already failed; a cleanup error must not hide that failure.
        }
    }
}
