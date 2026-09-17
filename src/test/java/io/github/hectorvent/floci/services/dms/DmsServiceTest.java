package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DmsServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String VPC_ID = "vpc-0dms";

    private final ObjectMapper mapper = new ObjectMapper();
    private DmsService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<ReplicationSubnetGroup> store =
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("dms"), eq("dms-replication-subnet-groups.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);

        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.describeSubnets(eq(REGION), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> requested = invocation.getArgument(1);
            return requested.stream().map(DmsServiceTest::subnet).filter(java.util.Objects::nonNull).toList();
        });
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        service = new DmsService(storageFactory, ec2Service, regionResolver);
    }

    @Test
    void createStoresGroupUnderLowercasedIdentifier() {
        service.createReplicationSubnetGroup(
                createRequest("Tf-Example", "example", "subnet-a", "subnet-b"), REGION);

        List<ReplicationSubnetGroup> described =
                service.describeReplicationSubnetGroups(filterRequest("tf-example"), REGION).items();

        assertEquals(1, described.size());
        ReplicationSubnetGroup group = described.getFirst();
        assertEquals("tf-example", group.getReplicationSubnetGroupIdentifier());
        assertEquals("example", group.getReplicationSubnetGroupDescription());
        assertEquals(VPC_ID, group.getVpcId());
        assertEquals("Complete", group.getSubnetGroupStatus());
        assertEquals(List.of("subnet-a", "subnet-b"), group.getSubnetIds());
        assertEquals(Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1b"),
                group.getSubnetAvailabilityZones());
        assertEquals(List.of("IPV4"), group.getSupportedNetworkTypes());
    }

    @Test
    void createRejectsDuplicateIdentifierRegardlessOfCase() {
        service.createReplicationSubnetGroup(createRequest("dup", "first", "subnet-a", "subnet-b"), REGION);

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("DUP", "second", "subnet-a", "subnet-b"), REGION));
        assertEquals("ResourceAlreadyExistsFault", duplicate.getErrorCode());
    }

    @Test
    void createRejectsUnknownSubnets() {
        AwsException invalid = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("bad-subnets", "example", "subnet-a", "subnet-missing"), REGION));
        assertEquals("InvalidSubnet", invalid.getErrorCode());
    }

    @Test
    void createRejectsSubnetsInASingleAvailabilityZone() {
        AwsException tooFewZones = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("one-az", "example", "subnet-a"), REGION));
        assertEquals("ReplicationSubnetGroupDoesNotCoverEnoughAZs", tooFewZones.getErrorCode());
    }

    @Test
    void createRejectsTwoSubnetsSharingOneAvailabilityZone() {
        AwsException tooFewZones = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("same-az", "example", "subnet-a", "subnet-a2"), REGION));
        assertEquals("ReplicationSubnetGroupDoesNotCoverEnoughAZs", tooFewZones.getErrorCode());
    }

    @Test
    void createRejectsASubnetWithNoAvailabilityZone() {
        AwsException noZone = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("no-az", "example", "subnet-a", "subnet-no-az"), REGION));
        assertEquals("InvalidSubnet", noZone.getErrorCode());
    }

    @Test
    void createRejectsSubnetsSpanningVpcs() {
        AwsException crossVpc = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("cross-vpc", "example", "subnet-a", "subnet-other-vpc"), REGION));
        assertEquals("InvalidSubnet", crossVpc.getErrorCode());
    }

    @Test
    void createRejectsMissingDescription() {
        ObjectNode request = createRequest("no-description", null, "subnet-a", "subnet-b");
        AwsException missing = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("InvalidParameterValueException", missing.getErrorCode());
    }

    @Test
    void createRejectsReservedDefaultIdentifier() {
        AwsException reserved = assertThrows(AwsException.class, () -> service.createReplicationSubnetGroup(
                createRequest("Default", "example", "subnet-a", "subnet-b"), REGION));
        assertEquals("InvalidParameterValueException", reserved.getErrorCode());
    }

    @Test
    void describeIsScopedToTheRequestRegion() {
        service.createReplicationSubnetGroup(createRequest("scoped", "example", "subnet-a", "subnet-b"), REGION);

        assertTrue(service.describeReplicationSubnetGroups(mapper.createObjectNode(), "eu-west-1")
                .items().isEmpty());
    }

    @Test
    void describeOfMissingGroupFaults() {
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(filterRequest("absent"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void describeWalksEveryGroupAcrossPagesWithoutDuplicatesOrOmissions() {
        List<String> created = createGroups(25);

        PaginatedResult<ReplicationSubnetGroup> first =
                service.describeReplicationSubnetGroups(pageRequest(20, null), REGION);
        assertEquals(20, first.items().size());
        assertNotNull(first.nextToken());

        PaginatedResult<ReplicationSubnetGroup> second =
                service.describeReplicationSubnetGroups(pageRequest(20, first.nextToken()), REGION);
        assertEquals(5, second.items().size());
        assertNull(second.nextToken(), "the final page must not carry a Marker");

        List<String> walked = new ArrayList<>();
        first.items().forEach(group -> walked.add(group.getReplicationSubnetGroupIdentifier()));
        second.items().forEach(group -> walked.add(group.getReplicationSubnetGroupIdentifier()));
        assertEquals(created, walked);
    }

    @Test
    void describeResumesAfterTheMarkerEvenWhenAnEarlierGroupIsDeleted() {
        createGroups(25);

        PaginatedResult<ReplicationSubnetGroup> first =
                service.describeReplicationSubnetGroups(pageRequest(20, null), REGION);
        service.deleteReplicationSubnetGroup(identifierRequest("page-00"), REGION);

        PaginatedResult<ReplicationSubnetGroup> second =
                service.describeReplicationSubnetGroups(pageRequest(20, first.nextToken()), REGION);

        assertEquals(List.of("page-20", "page-21", "page-22", "page-23", "page-24"),
                second.items().stream().map(ReplicationSubnetGroup::getReplicationSubnetGroupIdentifier).toList());
    }

    @Test
    void describeDefaultsToOneHundredRecordsPerPage() {
        createGroups(25);

        PaginatedResult<ReplicationSubnetGroup> page =
                service.describeReplicationSubnetGroups(mapper.createObjectNode(), REGION);

        assertEquals(25, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void describeRejectsMaxRecordsBelowTwenty() {
        AwsException tooSmall = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(pageRequest(19, null), REGION));
        assertEquals("InvalidParameterValueException", tooSmall.getErrorCode());
    }

    @Test
    void describeRejectsMaxRecordsAboveOneHundred() {
        AwsException tooLarge = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(pageRequest(101, null), REGION));
        assertEquals("InvalidParameterValueException", tooLarge.getErrorCode());
    }

    @Test
    void describeRejectsMaxRecordsThatIsNotAnInteger() {
        ObjectNode request = mapper.createObjectNode();
        request.put("MaxRecords", "20");

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void createRejectsADescriptionCarryingAControlCharacter() {
        ObjectNode request = createRequest("control-char", "terraform\u0001managed", "subnet-a", "subnet-b");

        AwsException nonPrintable = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("InvalidParameterValueException", nonPrintable.getErrorCode());
        AwsException notStored = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(filterRequest("control-char"), REGION));
        assertEquals("ResourceNotFoundFault", notStored.getErrorCode());
    }

    @Test
    void deleteRemovesTheGroup() {
        service.createReplicationSubnetGroup(createRequest("doomed", "example", "subnet-a", "subnet-b"), REGION);
        service.deleteReplicationSubnetGroup(identifierRequest("doomed"), REGION);

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(filterRequest("doomed"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void deleteOfMissingGroupFaults() {
        AwsException notFound = assertThrows(AwsException.class,
                () -> service.deleteReplicationSubnetGroup(identifierRequest("absent"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void clearRemovesPersistedState() {
        service.createReplicationSubnetGroup(createRequest("reset-me", "example", "subnet-a", "subnet-b"), REGION);
        service.clear();

        assertTrue(service.describeReplicationSubnetGroups(mapper.createObjectNode(), REGION).items().isEmpty());
    }

    @Test
    void createStoresTagsReadableThroughListTagsForResource() {
        ObjectNode request = createRequest("tagged", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test", "team", "platform");
        service.createReplicationSubnetGroup(request, REGION);

        assertEquals(Map.of("env", "test", "team", "platform"), tagsOf("tagged"));
    }

    @Test
    void addTagsMergesWithExistingTagsAndOverwritesByKey() {
        ObjectNode request = createRequest("merge-me", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test");
        service.createReplicationSubnetGroup(request, REGION);

        ObjectNode addRequest = mapper.createObjectNode();
        addRequest.put("ResourceArn", arn("merge-me"));
        tagList(addRequest.putArray("Tags"), "env", "prod", "owner", "data");
        service.addTagsToResource(addRequest, REGION);

        assertEquals(Map.of("env", "prod", "owner", "data"), tagsOf("merge-me"));
    }

    @Test
    void removeTagsDropsOnlyTheNamedKeys() {
        ObjectNode request = createRequest("trim-me", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test", "owner", "data");
        service.createReplicationSubnetGroup(request, REGION);

        ObjectNode removeRequest = mapper.createObjectNode();
        removeRequest.put("ResourceArn", arn("trim-me"));
        removeRequest.putArray("TagKeys").add("env").add("absent-key");
        service.removeTagsFromResource(removeRequest, REGION);

        assertEquals(Map.of("owner", "data"), tagsOf("trim-me"));
    }

    @Test
    void listTagsByArnListCarriesTheOwningArnOnEachTag() {
        ObjectNode request = createRequest("arn-list", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test");
        service.createReplicationSubnetGroup(request, REGION);

        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.putArray("ResourceArnList").add(arn("arn-list"));
        List<ResourceTag> tags = service.listTagsForResource(listRequest, REGION);

        assertEquals(List.of(new ResourceTag(arn("arn-list"), "env", "test")), tags);
    }

    @Test
    void listTagsBySingleArnOmitsTheArnOnEachTag() {
        ObjectNode request = createRequest("single-arn", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test");
        service.createReplicationSubnetGroup(request, REGION);

        assertEquals(List.of(new ResourceTag(null, "env", "test")),
                service.listTagsForResource(arnRequest("single-arn"), REGION));
    }

    @Test
    void tagOperationsOnAnUnknownArnFault() {
        String missing = "arn:aws:dms:" + REGION + ":" + ACCOUNT_ID + ":subgrp:not-there";
        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.put("ResourceArn", missing);

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.listTagsForResource(listRequest, REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void tagOperationsOnAnotherAccountsArnFault() {
        ObjectNode request = createRequest("other-account", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test");
        service.createReplicationSubnetGroup(request, REGION);

        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.put("ResourceArn", "arn:aws:dms:" + REGION + ":999999999999:subgrp:other-account");

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.listTagsForResource(listRequest, REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void tagOperationsOnAnotherRegionsArnFault() {
        ObjectNode request = createRequest("other-region", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test");
        service.createReplicationSubnetGroup(request, REGION);

        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.put("ResourceArn", "arn:aws:dms:eu-west-1:" + ACCOUNT_ID + ":subgrp:other-region");

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.listTagsForResource(listRequest, REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void tagOperationsOnANonSubnetGroupArnFault() {
        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.put("ResourceArn", "arn:aws:dms:" + REGION + ":" + ACCOUNT_ID + ":rep:Example");

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.listTagsForResource(listRequest, REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void reservedTagKeyPrefixesAreRejected() {
        ObjectNode request = createRequest("reserved-tag", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "aws:created-by", "someone");

        AwsException reserved = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("InvalidParameterValueException", reserved.getErrorCode());
    }

    @Test
    void deletingAGroupDropsItsTags() {
        ObjectNode request = createRequest("tags-die", "example", "subnet-a", "subnet-b");
        tagList(request.putArray("Tags"), "env", "test");
        service.createReplicationSubnetGroup(request, REGION);
        service.deleteReplicationSubnetGroup(identifierRequest("tags-die"), REGION);

        AwsException notFound = assertThrows(AwsException.class,
                () -> service.listTagsForResource(arnRequest("tags-die"), REGION));
        assertEquals("ResourceNotFoundFault", notFound.getErrorCode());
    }

    @Test
    void createRejectsATagValueThatIsNotAString() {
        ObjectNode request = createRequest("object-tag-value", "example", "subnet-a", "subnet-b");
        request.putArray("Tags").addObject().put("Key", "env").putObject("Value").put("nested", 1);

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void createRejectsATagsMemberThatIsNotAList() {
        ObjectNode request = createRequest("scalar-tags", "example", "subnet-a", "subnet-b");
        request.put("Tags", "env=test");

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void createRejectsASubnetIdThatIsNotAString() {
        ObjectNode request = identifierRequest("numeric-subnet");
        request.put("ReplicationSubnetGroupDescription", "example");
        request.putArray("SubnetIds").add("subnet-a").add(42);

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void describeRejectsFilterValuesThatAreNotAList() {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode filter = request.putArray("Filters").addObject();
        filter.put("Name", "replication-subnet-group-id");
        filter.put("Values", "tf-example");

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void describeRejectsAFilterValueThatIsNotAString() {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode filter = request.putArray("Filters").addObject();
        filter.put("Name", "replication-subnet-group-id");
        filter.putArray("Values").addObject().put("Value", "tf-example");

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.describeReplicationSubnetGroups(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void listTagsRejectsAnArnListElementThatIsNotAString() {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("ResourceArnList").addObject().put("ResourceArn", arn("whatever"));

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.listTagsForResource(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void removeTagsRejectsATagKeyThatIsNotAString() {
        ObjectNode request = createRequest("bad-tag-keys", "example", "subnet-a", "subnet-b");
        service.createReplicationSubnetGroup(request, REGION);

        ObjectNode removeRequest = mapper.createObjectNode();
        removeRequest.put("ResourceArn", arn("bad-tag-keys"));
        removeRequest.putArray("TagKeys").add(7);

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.removeTagsFromResource(removeRequest, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    @Test
    void createRejectsAnIdentifierThatIsNotAString() {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationSubnetGroupIdentifier", 12345);
        request.put("ReplicationSubnetGroupDescription", "example");
        request.putArray("SubnetIds").add("subnet-a").add("subnet-b");

        AwsException wrongType = assertThrows(AwsException.class,
                () -> service.createReplicationSubnetGroup(request, REGION));
        assertEquals("SerializationException", wrongType.getErrorCode());
    }

    private Map<String, String> tagsOf(String identifier) {
        return service.listTagsForResource(arnRequest(identifier), REGION).stream()
                .collect(java.util.stream.Collectors.toMap(ResourceTag::key, ResourceTag::value));
    }

    private ObjectNode arnRequest(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        request.put("ResourceArn", arn(identifier));
        return request;
    }

    private String arn(String identifier) {
        return "arn:aws:dms:" + REGION + ":" + ACCOUNT_ID + ":subgrp:" + identifier;
    }

    private static void tagList(ArrayNode tags, String... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.addObject().put("Key", keysAndValues[i]).put("Value", keysAndValues[i + 1]);
        }
    }

    private ObjectNode createRequest(String identifier, String description, String... subnetIds) {
        ObjectNode request = identifierRequest(identifier);
        if (description != null) {
            request.put("ReplicationSubnetGroupDescription", description);
        }
        ArrayNode subnets = request.putArray("SubnetIds");
        for (String subnetId : subnetIds) {
            subnets.add(subnetId);
        }
        return request;
    }

    private ObjectNode identifierRequest(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        request.put("ReplicationSubnetGroupIdentifier", identifier);
        return request;
    }

    private List<String> createGroups(int count) {
        List<String> identifiers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String identifier = String.format("page-%02d", i);
            service.createReplicationSubnetGroup(
                    createRequest(identifier, "example", "subnet-a", "subnet-b"), REGION);
            identifiers.add(identifier);
        }
        return identifiers;
    }

    private ObjectNode pageRequest(int maxRecords, String marker) {
        ObjectNode request = mapper.createObjectNode();
        request.put("MaxRecords", maxRecords);
        if (marker != null) {
            request.put("Marker", marker);
        }
        return request;
    }

    private ObjectNode filterRequest(String identifier) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode filter = request.putArray("Filters").addObject();
        filter.put("Name", "replication-subnet-group-id");
        filter.putArray("Values").add(identifier);
        return request;
    }

    private static Subnet subnet(String subnetId) {
        return switch (subnetId) {
            case "subnet-a" -> subnet(subnetId, VPC_ID, "us-east-1a");
            case "subnet-b" -> subnet(subnetId, VPC_ID, "us-east-1b");
            case "subnet-a2" -> subnet(subnetId, VPC_ID, "us-east-1a");
            case "subnet-no-az" -> subnet(subnetId, VPC_ID, null);
            case "subnet-other-vpc" -> subnet(subnetId, "vpc-0other", "us-east-1b");
            default -> null;
        };
    }

    private static Subnet subnet(String subnetId, String vpcId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setRegion(REGION);
        return subnet;
    }
}
