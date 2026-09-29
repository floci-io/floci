package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGroupsTaggingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:q1";
    private static final String INSTANCE_ARN = "arn:aws:ec2:us-east-1:000000000000:instance/i-1";
    private static final String API_KEY_ARN = "arn:aws:apigateway:us-east-1::/apikeys/k1";
    private static final String IOT_ARN = "arn:aws:iot:us-east-1:000000000000:thing/t1";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/r";

    @Test
    void parsedTypeCutsAtSlashOrColonAfterLeadingSlash() {
        assertEquals("instance", ResourceGroupsTaggingService.parsedType("instance/i-1"));
        assertEquals("function", ResourceGroupsTaggingService.parsedType("function:f"));
        assertEquals("log-group", ResourceGroupsTaggingService.parsedType("log-group:/a/b"));
        assertEquals("apikeys", ResourceGroupsTaggingService.parsedType("/apikeys/k"));
        assertEquals("restapis", ResourceGroupsTaggingService.parsedType("/restapis/a/stages/s"));
        assertEquals("my-bucket", ResourceGroupsTaggingService.parsedType("my-bucket"));
    }

    @Test
    void resourceTypeFilterMatchesProviderDeclaredType() {
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("team", "a")))), List.of());

        List<ResourceTagMapping> items = service.getResources(
                List.of(), List.of(), List.of("sqs:queue"), null, 0, REGION).items();

        assertEquals(List.of(QUEUE_ARN), arns(items));
    }

    @Test
    void resourceTypeFilterMatchesColonAndLeadingSlashArns() {
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:f1";
        String logGroupArn = "arn:aws:logs:us-east-1:000000000000:log-group:/a/b";
        ResourceGroupsTaggingService service = service(List.of(), List.of());
        service.tagResources(List.of(functionArn, logGroupArn, API_KEY_ARN), Map.of("k", "v"), REGION);

        assertEquals(List.of(functionArn), arns(filterByType(service, "lambda:function")));
        assertEquals(List.of(logGroupArn), arns(filterByType(service, "logs:log-group")));
        assertEquals(List.of(API_KEY_ARN), arns(filterByType(service, "apigateway:apikeys")));
        assertTrue(filterByType(service, "apigateway:/apikeys").isEmpty());
    }

    @Test
    void restApisFilterMatchesRestApisAndTheirStages() {
        String apiArn = "arn:aws:apigateway:us-east-1::/restapis/a";
        String stageArn = apiArn + "/stages/s";
        ResourceGroupsTaggingService service = service(List.of(provider(
                resource(apiArn, "apigateway:restapis", Map.of("k", "v")),
                resource(stageArn, "apigateway:restapis/stages", Map.of("k", "v")))), List.of());

        assertEquals(List.of(apiArn, stageArn), arns(filterByType(service, "apigateway:restapis")));
        assertEquals(List.of(stageArn), arns(filterByType(service, "apigateway:restapis/stages")));
    }

    @Test
    void providerResourceWithTagsIsListed() {
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("team", "a")))), List.of());

        List<ResourceTagMapping> items = allResources(service);

        assertEquals(List.of(QUEUE_ARN), arns(items));
        assertEquals(Map.of("team", "a"), items.getFirst().getTags());
    }

    @Test
    void untaggedProviderResourceIsNotListed() {
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of()))), List.of());

        assertTrue(allResources(service).isEmpty());
    }

    @Test
    void providerTagsOverlayStoreTagsForSameArn() {
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("b", "provider", "c", "provider")))), List.of());
        service.tagResources(List.of(QUEUE_ARN), Map.of("a", "store", "b", "store"), REGION);

        List<ResourceTagMapping> items = allResources(service);

        assertEquals(1, items.size());
        assertEquals(Map.of("a", "store", "b", "provider", "c", "provider"), items.getFirst().getTags());
        assertEquals(Map.of("a", "store", "b", "store"), service.getTagsForResource(REGION, QUEUE_ARN));
    }

    @Test
    void logGroupWildcardSuffixIsStripped() {
        ResourceGroupsTaggingService service = service(List.of(provider(resource(
                "arn:aws:logs:us-east-1:000000000000:log-group:/app/x:*", "logs:log-group",
                Map.of("team", "a")))), List.of());

        List<ResourceTagMapping> items = service.getResources(
                List.of(), List.of(), List.of("logs:log-group"), null, 0, REGION).items();

        assertEquals(List.of("arn:aws:logs:us-east-1:000000000000:log-group:/app/x"), arns(items));
    }

    @Test
    void otherRegionAndOtherAccountAreHidden() {
        String otherRegion = "arn:aws:sqs:us-west-2:000000000000:q-west";
        String otherAccount = "arn:aws:sqs:us-east-1:111111111111:q-foreign";
        ResourceGroupsTaggingService service = service(List.of(provider(
                resource(otherRegion, "sqs:queue", Map.of("k", "v")),
                resource(otherAccount, "sqs:queue", Map.of("k", "v")),
                resource(QUEUE_ARN, "sqs:queue", Map.of("k", "v")))), List.of());

        assertEquals(List.of(QUEUE_ARN), arns(allResources(service)));
    }

    @Test
    void otherAccountArnIsReportedAndNotStored() {
        String foreignArn = "arn:aws:sqs:us-east-1:111111111111:q-foreign";
        ResourceGroupsTaggingService service = service(List.of(), List.of());

        Map<String, AwsException> tagFailures = service.applyTags(List.of(foreignArn, QUEUE_ARN),
                Map.of("k", "v"), REGION);

        assertEquals(Set.of(foreignArn), tagFailures.keySet());
        assertEquals("AccessDeniedException", tagFailures.get(foreignArn).getErrorCode());
        assertEquals(403, tagFailures.get(foreignArn).getHttpStatus());
        assertTrue(service.getTagsForResource(REGION, foreignArn).isEmpty());
        assertEquals(Map.of("k", "v"), service.getTagsForResource(REGION, QUEUE_ARN));

        Map<String, AwsException> untagFailures = service.removeTags(List.of(foreignArn), List.of("k"), REGION);

        assertEquals(Set.of(foreignArn), untagFailures.keySet());
        assertEquals("AccessDeniedException", untagFailures.get(foreignArn).getErrorCode());
        assertEquals(403, untagFailures.get(foreignArn).getHttpStatus());
    }

    @Test
    void providerResourceWithoutArnRegionUsesItsOwnRegion() {
        String bucketArn = "arn:aws:s3:::b";
        ResourceGroupsTaggingService service = service(List.of(provider(new ExplorerResource(
                bucketArn, "s3:bucket", "s3", "eu-west-1", ACCOUNT, Instant.EPOCH, Map.of("k", "v")))), List.of());

        assertTrue(resourcesIn(service, REGION).isEmpty());
        assertEquals(List.of(bucketArn), arns(resourcesIn(service, "eu-west-1")));
    }

    @Test
    void storeCopyOfProviderResourceInAnotherRegionIsHidden() {
        String bucketArn = "arn:aws:s3:::b";
        ResourceGroupsTaggingService service = service(List.of(provider(new ExplorerResource(
                bucketArn, "s3:bucket", "s3", "eu-west-1", ACCOUNT, Instant.EPOCH, Map.of("k", "v")))), List.of());
        service.tagResources(List.of(bucketArn), Map.of("team", "a"), REGION);

        List<ResourceTagMapping> west = resourcesIn(service, "eu-west-1");

        assertTrue(resourcesIn(service, REGION).isEmpty());
        assertEquals(List.of(bucketArn), arns(west));
        assertEquals(Map.of("team", "a", "k", "v"), west.getFirst().getTags());
    }

    @Test
    void globalProviderResourceIsNotListedInAnyRegion() {
        ResourceGroupsTaggingService service = service(List.of(provider(globalRole(Map.of("k", "v")),
                resource("arn:aws:iam::000000000000:user/u1", "iam:user", Map.of("k", "v")))), List.of());

        assertTrue(resourcesIn(service, REGION).isEmpty());
        assertTrue(resourcesIn(service, "eu-west-1").isEmpty());
        assertTrue(service.getTagKeys(null, 0, REGION).items().isEmpty());
    }

    @Test
    void storeEntryForGlobalProviderResourceIsNotListed() {
        ResourceGroupsTaggingService service = service(List.of(provider(globalRole(Map.of("k", "v")))), List.of());
        service.tagResources(List.of(ROLE_ARN), Map.of("team", "a"), REGION);

        assertTrue(allResources(service).isEmpty());
    }

    @Test
    void storeOnlyIamArnIsNotListed() {
        ResourceGroupsTaggingService service = service(List.of(), List.of());
        service.tagResources(List.of(ROLE_ARN), Map.of("team", "a"), REGION);

        assertTrue(allResources(service).isEmpty());
    }

    @Test
    void failingProviderIsSkipped() {
        ResourceGroupsTaggingService service = service(List.of(failingProvider(),
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("k", "v")))), List.of());

        assertEquals(List.of(QUEUE_ARN), arns(allResources(service)));
    }

    @Test
    void tagResourcesFailsWhenAnOwnerCannotBeRead() {
        RecordingTagHandler handler = new RecordingTagHandler("sqs", false);
        ResourceGroupsTaggingService service = service(List.of(failingProvider()), List.of(handler));

        Map<String, AwsException> failures = service.applyTags(List.of(QUEUE_ARN), Map.of("k", "v"), REGION);

        assertEquals(Set.of(QUEUE_ARN), failures.keySet());
        assertEquals("InternalServiceException", failures.get(QUEUE_ARN).getErrorCode());
        assertEquals(500, failures.get(QUEUE_ARN).getHttpStatus());
        assertTrue(handler.tagged.isEmpty());
        assertTrue(service.getTagsForResource(REGION, QUEUE_ARN).isEmpty());
        assertTrue(allResources(service).isEmpty());
    }

    @Test
    void untagResourcesFailsWhenAnOwnerCannotBeRead() {
        RecordingTagHandler handler = new RecordingTagHandler("sqs", false);
        ResourceGroupsTaggingService service = service(List.of(failingProvider()), List.of(handler));
        service.tagResources(List.of(QUEUE_ARN), Map.of("k", "v"), REGION);

        Map<String, AwsException> failures = service.removeTags(List.of(QUEUE_ARN), List.of("k"), REGION);

        assertEquals(Set.of(QUEUE_ARN), failures.keySet());
        assertEquals("InternalServiceException", failures.get(QUEUE_ARN).getErrorCode());
        assertEquals(500, failures.get(QUEUE_ARN).getHttpStatus());
        assertTrue(handler.untagged.isEmpty());
        assertTrue(service.getTagsForResource(REGION, QUEUE_ARN).isEmpty());
    }

    @Test
    void listedOwnerIsTaggedWhenAnotherProviderFails() {
        RecordingTagHandler handler = new RecordingTagHandler("sqs", false);
        ResourceGroupsTaggingService service = service(List.of(failingProvider(),
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of()))), List.of(handler));

        assertTrue(service.applyTags(List.of(QUEUE_ARN), Map.of("k", "v"), REGION).isEmpty());
        assertTrue(service.removeTags(List.of(QUEUE_ARN), List.of("k"), REGION).isEmpty());

        assertEquals(Map.of("k", "v"), handler.tagged.get(QUEUE_ARN));
        assertEquals(List.of("k"), handler.untagged.get(QUEUE_ARN));
        assertTrue(service.getTagsForResource(REGION, QUEUE_ARN).isEmpty());
    }

    @Test
    void storeOnlyWriteSucceedsWhenAProviderCannotBeRead() {
        RecordingTagHandler handler = new RecordingTagHandler("sqs", false);
        ResourceGroupsTaggingService service = service(List.of(failingProvider()), List.of(handler));

        service.applyTags(List.of(INSTANCE_ARN), Map.of("a", "1", "b", "2"), REGION);
        service.removeTags(List.of(INSTANCE_ARN), List.of("a"), REGION);

        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, INSTANCE_ARN));
        assertTrue(handler.tagged.isEmpty());
    }

    @Test
    void providerWhoseTagsFailToCopyIsSkipped() {
        Map<String, String> liveTags = new AbstractMap<>() {
            @Override
            public Set<Entry<String, String>> entrySet() {
                throw new ConcurrentModificationException();
            }
        };
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(INSTANCE_ARN, "ec2:instance", liveTags)),
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("k", "v")))), List.of());

        assertEquals(List.of(QUEUE_ARN), arns(allResources(service)));
    }

    @Test
    void getTagKeysAndValuesIncludeProviderTags() {
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("pk", "pv")))), List.of());
        service.tagResources(List.of(INSTANCE_ARN), Map.of("sk", "sv"), REGION);

        List<String> keys = arns(service.getTagKeys(null, 0, REGION).items());
        List<String> values = arns(service.getTagValues("pk", null, 0, REGION).items());

        assertEquals(List.of("pk", "sk"), keys);
        assertEquals(List.of("pv"), values);
    }

    @Test
    void paginationSpansStoreAndProviderEntries() {
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(QUEUE_ARN, "sqs:queue", Map.of("k", "v")))), List.of());
        service.tagResources(List.of(INSTANCE_ARN), Map.of("k", "v"), REGION);

        ResourceGroupsTaggingService.PageResult first = service.getResources(
                List.of(), List.of(), List.of(), null, 1, REGION);
        ResourceGroupsTaggingService.PageResult second = service.getResources(
                List.of(), List.of(), List.of(), first.nextPaginationToken(), 1, REGION);

        assertEquals(List.of(INSTANCE_ARN), arns(first.items()));
        assertEquals(List.of(QUEUE_ARN), arns(second.items()));
        assertNull(second.nextPaginationToken());
    }

    @Test
    void staleTokenPastTheEndReturnsAnEmptyPage() {
        ResourceGroupsTaggingService service = service(List.of(), List.of());
        service.tagResources(List.of(INSTANCE_ARN), Map.of("a", "v1"), REGION);
        service.tagResources(List.of(QUEUE_ARN), Map.of("a", "v2", "b", "x"), REGION);
        String resourcesToken = service.getResources(
                List.of(), List.of(), List.of(), null, 1, REGION).nextPaginationToken();
        String keysToken = service.getTagKeys(null, 1, REGION).nextPaginationToken();
        String valuesToken = service.getTagValues("a", null, 1, REGION).nextPaginationToken();

        service.deleteResources(List.of(INSTANCE_ARN, QUEUE_ARN), REGION);
        ResourceGroupsTaggingService.PageResult resources = service.getResources(
                List.of(), List.of(), List.of(), resourcesToken, 1, REGION);
        ResourceGroupsTaggingService.PageResult keys = service.getTagKeys(keysToken, 1, REGION);
        ResourceGroupsTaggingService.PageResult values = service.getTagValues("a", valuesToken, 1, REGION);

        assertTrue(resources.items().isEmpty());
        assertNull(resources.nextPaginationToken());
        assertTrue(keys.items().isEmpty());
        assertNull(keys.nextPaginationToken());
        assertTrue(values.items().isEmpty());
        assertNull(values.nextPaginationToken());
    }

    @Test
    void malformedStoreArnStillListedWithoutFilters() {
        ResourceGroupsTaggingService service = service(List.of(), List.of());
        service.tagResources(List.of("not-an-arn"), Map.of("k", "v"), REGION);

        assertEquals(List.of("not-an-arn"), arns(allResources(service)));
    }

    @Test
    void listedArnWithHandlerIsForwardedAndNotStored() {
        RecordingTagHandler handler = new RecordingTagHandler("apigateway", false);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(API_KEY_ARN, "apigateway:apikeys", Map.of()))), List.of(handler));

        Map<String, AwsException> failures = service.applyTags(List.of(API_KEY_ARN), Map.of("cid", "c2"), REGION);

        assertTrue(failures.isEmpty());
        assertEquals(Map.of("cid", "c2"), handler.tagged.get(API_KEY_ARN));
        assertEquals(List.of(REGION), handler.regions);
        assertTrue(service.getTagsForResource(REGION, API_KEY_ARN).isEmpty());
    }

    @Test
    void listedArnMatchesWithoutWildcardSuffix() {
        String logGroupArn = "arn:aws:logs:us-east-1:000000000000:log-group:/a";
        RecordingTagHandler handler = new RecordingTagHandler("logs", false);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(logGroupArn + ":*", "logs:log-group", Map.of()))), List.of(handler));

        service.applyTags(List.of(logGroupArn), Map.of("k", "v"), REGION);

        assertEquals(Map.of("k", "v"), handler.tagged.get(logGroupArn));
        assertTrue(service.getTagsForResource(REGION, logGroupArn).isEmpty());
    }

    @Test
    void unlistedArnWithHandlerGoesToStoreWithoutCallingHandler() {
        String deploymentArn = "arn:aws:apigateway:us-east-1::/restapis/abc/deployments/d1";
        RecordingTagHandler handler = new RecordingTagHandler("apigateway", false);
        ResourceGroupsTaggingService service = service(List.of(provider(resource(
                "arn:aws:apigateway:us-east-1::/restapis/abc", "apigateway:restapis", Map.of()))),
                List.of(handler));

        Map<String, AwsException> failures = service.applyTags(List.of(deploymentArn), Map.of("k", "v"), REGION);

        assertTrue(failures.isEmpty());
        assertTrue(handler.tagged.isEmpty());
        assertEquals(Map.of("k", "v"), service.getTagsForResource(REGION, deploymentArn));
    }

    @Test
    void ownerInAnotherRegionIsNotForwarded() {
        String bucketArn = "arn:aws:s3:::b";
        RecordingTagHandler handler = new RecordingTagHandler("s3", false);
        ResourceGroupsTaggingService service = service(List.of(provider(new ExplorerResource(
                bucketArn, "s3:bucket", "s3", "us-west-2", ACCOUNT, Instant.EPOCH, Map.of()))), List.of(handler));

        service.applyTags(List.of(bucketArn), Map.of("a", "1", "b", "2"), REGION);
        service.removeTags(List.of(bucketArn), List.of("a"), REGION);

        assertTrue(handler.tagged.isEmpty());
        assertTrue(handler.untagged.isEmpty());
        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, bucketArn));
    }

    @Test
    void applyTagsPassesRequestRegionWhenArnHasNone() {
        String bucketArn = "arn:aws:s3:::b";
        RecordingTagHandler handler = new RecordingTagHandler("s3", false);
        ResourceGroupsTaggingService service = service(List.of(provider(new ExplorerResource(
                bucketArn, "s3:bucket", "s3", "eu-west-1", ACCOUNT, Instant.EPOCH, Map.of()))), List.of(handler));

        service.applyTags(List.of(bucketArn), Map.of("k", "v"), "eu-west-1");

        assertEquals(List.of("eu-west-1"), handler.regions);
    }

    @Test
    void globalProviderResourceIsNotRoutedToItsOwner() {
        RecordingTagHandler handler = new RecordingTagHandler("iam", false);
        ResourceGroupsTaggingService service = service(List.of(provider(globalRole(Map.of()))), List.of(handler));

        service.applyTags(List.of(ROLE_ARN), Map.of("k", "v"), REGION);

        assertTrue(handler.tagged.isEmpty());
        assertEquals(Map.of("k", "v"), service.getTagsForResource(REGION, ROLE_ARN));
    }

    @Test
    void applyTagsRejectsArnFromAnotherRegionAndWritesNothing() {
        String westQueueArn = "arn:aws:sqs:us-west-2:000000000000:q-west";
        RecordingTagHandler handler = new RecordingTagHandler("apigateway", false);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(API_KEY_ARN, "apigateway:apikeys", Map.of()))), List.of(handler));

        AwsException e = assertThrows(AwsException.class, () -> service.applyTags(
                List.of(API_KEY_ARN, QUEUE_ARN, westQueueArn), Map.of("k", "v"), REGION));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Region in the ARN " + westQueueArn
                + " does not match with the region in which TagResources API is invoked", e.getMessage());
        assertTrue(handler.tagged.isEmpty());
        assertTrue(service.getTagsForResource(REGION, QUEUE_ARN).isEmpty());
        assertTrue(service.getTagsForResource(REGION, westQueueArn).isEmpty());
    }

    @Test
    void removeTagsRejectsArnFromAnotherRegionAndRemovesNothing() {
        String westQueueArn = "arn:aws:sqs:us-west-2:000000000000:q-west";
        RecordingTagHandler handler = new RecordingTagHandler("apigateway", false);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(API_KEY_ARN, "apigateway:apikeys", Map.of()))), List.of(handler));
        service.tagResources(List.of(QUEUE_ARN), Map.of("k", "v"), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.removeTags(
                List.of(API_KEY_ARN, QUEUE_ARN, westQueueArn), List.of("k"), REGION));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Region in the ARN " + westQueueArn
                + " does not match with the region in which UntagResources API is invoked", e.getMessage());
        assertTrue(handler.untagged.isEmpty());
        assertEquals(Map.of("k", "v"), service.getTagsForResource(REGION, QUEUE_ARN));
    }

    @Test
    void handlerRejectionIsReturnedAndNotStored() {
        RecordingTagHandler handler = new RecordingTagHandler("apigateway", true);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(API_KEY_ARN, "apigateway:apikeys", Map.of()))), List.of(handler));

        Map<String, AwsException> failures = service.applyTags(
                List.of(API_KEY_ARN, QUEUE_ARN), Map.of("cid", "c2"), REGION);

        assertEquals(List.of(API_KEY_ARN), List.copyOf(failures.keySet()));
        assertEquals("NotFoundException", failures.get(API_KEY_ARN).getErrorCode());
        assertTrue(service.getTagsForResource(REGION, API_KEY_ARN).isEmpty());
        assertEquals(Map.of("cid", "c2"), service.getTagsForResource(REGION, QUEUE_ARN));
    }

    @Test
    void applyTagsWithoutHandlerWritesStore() {
        ResourceGroupsTaggingService service = service(List.of(), List.of(new RecordingTagHandler("iot", false)));

        Map<String, AwsException> failures = service.applyTags(List.of(QUEUE_ARN), Map.of("k", "v"), REGION);

        assertTrue(failures.isEmpty());
        assertEquals(Map.of("k", "v"), service.getTagsForResource(REGION, QUEUE_ARN));
    }

    @Test
    void removeTagsCallsHandlerAndUntagsStore() {
        RecordingTagHandler handler = new RecordingTagHandler("iot", false);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(IOT_ARN, "iot:thing", Map.of()))), List.of(handler));
        service.tagResources(List.of(IOT_ARN), Map.of("a", "1", "b", "2"), REGION);

        Map<String, AwsException> failures = service.removeTags(List.of(IOT_ARN), List.of("a"), REGION);

        assertTrue(failures.isEmpty());
        assertEquals(List.of("a"), handler.untagged.get(IOT_ARN));
        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, IOT_ARN));
    }

    @Test
    void removeTagsForUnlistedArnSkipsHandlerAndUntagsStore() {
        RecordingTagHandler handler = new RecordingTagHandler("iot", false);
        ResourceGroupsTaggingService service = service(List.of(), List.of(handler));
        service.tagResources(List.of(IOT_ARN), Map.of("a", "1", "b", "2"), REGION);

        service.removeTags(List.of(IOT_ARN), List.of("a"), REGION);

        assertTrue(handler.untagged.isEmpty());
        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, IOT_ARN));
    }

    @Test
    void removeTagsFailureIsReportedAndStoreStillUntagged() {
        RecordingTagHandler handler = new RecordingTagHandler("apigateway", true);
        ResourceGroupsTaggingService service = service(List.of(
                provider(resource(API_KEY_ARN, "apigateway:apikeys", Map.of()))), List.of(handler));
        service.tagResources(List.of(API_KEY_ARN, QUEUE_ARN), Map.of("a", "1", "b", "2"), REGION);

        Map<String, AwsException> failures = service.removeTags(List.of(API_KEY_ARN, QUEUE_ARN), List.of("a"), REGION);

        assertEquals(List.of(API_KEY_ARN), List.copyOf(failures.keySet()));
        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, API_KEY_ARN));
        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, QUEUE_ARN));
    }

    @Test
    void removeTagsClearsStoredWildcardLogGroupArnTheHandlerRejects() {
        String wildcardArn = "arn:aws:logs:us-east-1:000000000000:log-group:/a:*";
        RecordingTagHandler handler = new RecordingTagHandler("logs", true);
        ResourceGroupsTaggingService service = service(List.of(), List.of(handler));
        service.tagResources(List.of(wildcardArn), Map.of("a", "1", "b", "2"), REGION);

        Map<String, AwsException> failures = service.removeTags(List.of(wildcardArn), List.of("a"), REGION);

        assertEquals(List.of(wildcardArn), List.copyOf(failures.keySet()));
        assertEquals(Map.of("b", "2"), service.getTagsForResource(REGION, wildcardArn));
    }

    @Test
    void tagResourcesStaysStoreOnly() {
        RecordingTagHandler handler = new RecordingTagHandler("iot", false);
        ResourceGroupsTaggingService service = service(List.of(), List.of(handler));

        service.tagResources(List.of(IOT_ARN), Map.of("k", "v"), REGION);

        assertTrue(handler.tagged.isEmpty());
        assertEquals(Map.of("k", "v"), service.getTagsForResource(REGION, IOT_ARN));
    }

    private static ResourceGroupsTaggingService service(List<ResourceProvider> providers, List<TagHandler> handlers) {
        return new ResourceGroupsTaggingService(null, providers, handlers, new RegionResolver(REGION, ACCOUNT));
    }

    private static List<ResourceTagMapping> allResources(ResourceGroupsTaggingService service) {
        return resourcesIn(service, REGION);
    }

    private static List<ResourceTagMapping> resourcesIn(ResourceGroupsTaggingService service, String region) {
        return service.getResources(List.of(), List.of(), List.of(), null, 0, region).items();
    }

    private static List<ResourceTagMapping> filterByType(ResourceGroupsTaggingService service, String typeFilter) {
        return service.getResources(List.of(), List.of(), List.of(typeFilter), null, 0, REGION).items();
    }

    private static List<String> arns(List<ResourceTagMapping> items) {
        return items.stream().map(ResourceTagMapping::getResourceArn).toList();
    }

    private static ExplorerResource resource(String arn, String resourceType, Map<String, String> tags) {
        String[] parts = arn.split(":", 6);
        return new ExplorerResource(arn, resourceType, parts[2], parts[3], parts[4], Instant.EPOCH, tags);
    }

    // IamService reports its region-less ARNs with the region "global".
    private static ExplorerResource globalRole(Map<String, String> tags) {
        return new ExplorerResource(ROLE_ARN, "iam:role", "iam", "global", ACCOUNT, Instant.EPOCH, tags);
    }

    private static ResourceProvider provider(ExplorerResource... resources) {
        Set<SupportedResourceType> types = new HashSet<>();
        for (ExplorerResource resource : resources) {
            types.add(new SupportedResourceType(resource.resourceType(), resource.service(), true));
        }
        return new FakeProvider(List.of(resources), types);
    }

    private static ResourceProvider failingProvider() {
        return new ResourceProvider() {
            @Override
            public List<ExplorerResource> getResources() {
                throw new IllegalStateException("storage unavailable");
            }

            @Override
            public Set<SupportedResourceType> getSupportedResourceTypes() {
                return Set.of();
            }
        };
    }

    private record FakeProvider(List<ExplorerResource> resources, Set<SupportedResourceType> types)
            implements ResourceProvider {

        @Override
        public List<ExplorerResource> getResources() {
            return resources;
        }

        @Override
        public Set<SupportedResourceType> getSupportedResourceTypes() {
            return types;
        }
    }

    private static final class RecordingTagHandler implements TagHandler {

        private final String serviceKey;
        private final boolean reject;
        private final Map<String, Map<String, String>> tagged = new LinkedHashMap<>();
        private final Map<String, List<String>> untagged = new LinkedHashMap<>();
        private final List<String> regions = new ArrayList<>();

        private RecordingTagHandler(String serviceKey, boolean reject) {
            this.serviceKey = serviceKey;
            this.reject = reject;
        }

        @Override
        public String serviceKey() {
            return serviceKey;
        }

        @Override
        public Map<String, String> listTags(String region, String arn) {
            return tagged.getOrDefault(arn, Map.of());
        }

        @Override
        public void tagResource(String region, String arn, Map<String, String> tags) {
            if (reject) {
                throw new AwsException("NotFoundException", "Invalid resource ARN: " + arn, 404);
            }
            regions.add(region);
            tagged.computeIfAbsent(arn, ignored -> new LinkedHashMap<>()).putAll(tags);
        }

        @Override
        public void untagResource(String region, String arn, List<String> tagKeys) {
            if (reject) {
                throw new AwsException("NotFoundException", "Invalid resource ARN: " + arn, 404);
            }
            regions.add(region);
            untagged.put(arn, List.copyOf(tagKeys));
        }
    }
}
