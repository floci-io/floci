package io.floci.aws.services.resourcegroups;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.CapacityReservation;
import io.floci.aws.services.resourcegroups.model.ResourceGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceGroupsServiceTest {
    static final String ACCOUNT = "111122223333";
    static final String REGION = "eu-west-1";
    static final String CONFIGURATION = """
            [{"Type":"AWS::EC2::CapacityReservationPool"},
             {"Type":"AWS::ResourceGroups::Generic","Parameters":[
               {"Name":"allowed-resource-types","Values":["AWS::EC2::CapacityReservation"]}]}]
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CapacityReservation> reservations = new HashMap<>();
    private ResourceGroupsService service;
    private AccountAwareStorageBackend<ResourceGroup> groups;
    private Ec2Service ec2;
    private Clock clock;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        when(ec2.describeCapacityReservations(anyString(), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            CapacityReservation reservation = reservations.get(ids.getFirst());
            if (reservation == null) {
                throw new AwsException("InvalidCapacityReservationId.NotFound", "missing", 400);
            }
            return List.of(reservation);
        });
        groups = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new ResourceGroupsService(groups, ec2, clock);
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private JsonNode createRequest(String name) throws Exception {
        return mapper.readTree("{\"Name\":\"" + name + "\",\"Configuration\":" + CONFIGURATION
                + ",\"Description\":\"capacity pool\",\"Tags\":{\"purpose\":\"test\"}}");
    }

    private ResourceGroup create(String name) throws Exception {
        return service.create(REGION, ACCOUNT, createRequest(name));
    }

    private String reservation(int number) {
        String id = "cr-" + String.format("%017x", number);
        CapacityReservation reservation = new CapacityReservation();
        reservation.setCapacityReservationId(id);
        reservation.setCapacityReservationArn(
                AwsArnUtils.Arn.of("ec2", REGION, ACCOUNT, "capacity-reservation/" + id).toString());
        reservation.setOwnerId(ACCOUNT);
        reservation.setState("active");
        reservations.put(id, reservation);
        return reservation.getCapacityReservationArn();
    }

    private Map<String, Object> add(String group, String... arns) {
        return service.add(REGION, ACCOUNT, json(Map.of("Group", group, "ResourceArns", List.of(arns))));
    }

    private Map<String, Object> list(String group) {
        return service.list(REGION, ACCOUNT, json(Map.of("Group", group)));
    }

    @Test
    void storesConfigurationTagsAndDeprecatedNameSelector() throws Exception {
        ResourceGroup group = create("pool");
        assertEquals("arn:aws:resource-groups:eu-west-1:111122223333:group/pool", group.arn());
        assertEquals("capacity pool", group.description());
        assertEquals(Map.of("purpose", "test"), group.tags());
        assertEquals(2, group.configuration().size());
        assertEquals(group, service.get(REGION, ACCOUNT, json(Map.of("GroupName", "pool"))));
        assertEquals(group, mapper.readValue(mapper.writeValueAsBytes(group), ResourceGroup.class));
        assertThrows(AwsException.class, () -> create("pool"));
    }

    @Test
    void realMembershipIsIdempotentAndDeleteLeavesReservations() throws Exception {
        ResourceGroup group = create("pool");
        String arn = reservation(1);
        Map<String, Object> result = add(group.arn(), arn, arn);
        assertEquals(List.of(arn), result.get("Succeeded"));
        assertEquals(List.of(), result.get("Failed"));
        assertEquals(List.of(), result.get("Pending"));
        assertEquals(result, add("pool", arn));
        Map<String, String> identifier = Map.of("ResourceArn", arn, "ResourceType", ResourceGroupsService.RESOURCE_TYPE);
        assertEquals(List.of(identifier), list("pool").get("ResourceIdentifiers"));
        assertEquals(List.of(Map.of("Identifier", identifier)), list("pool").get("Resources"));
        service.delete(REGION, ACCOUNT, json(Map.of("GroupName", "pool")));
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> list("pool")).getErrorCode());
        assertEquals(1, reservations.size());
        create("pool");
        assertEquals(List.of(), list("pool").get("ResourceIdentifiers"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "cn-north-1", "us-iso-east-1", "eusc-de-east-1"})
    void derivesPartitionFromActualRegion(String region) throws Exception {
        ResourceGroup group = service.create(region, ACCOUNT, createRequest("pool"));
        assertEquals(AwsArnUtils.Arn.of("resource-groups", region, ACCOUNT, "group/pool").toString(), group.arn());
        assertEquals(group, service.get(region, ACCOUNT, json(Map.of("Group", "pool"))));
        if (!region.startsWith("eusc-")) {
            assertEquals(group, service.get(region, ACCOUNT, json(Map.of("Group", group.arn()))));
        }
    }

    @Test
    void accountRegionAndFullArnSelectorsAreIsolated() throws Exception {
        ResourceGroup group = create("pool");
        assertThrows(AwsException.class, () -> service.get(REGION, "444455556666", json(Map.of("Group", "pool"))));
        assertThrows(AwsException.class, () -> service.get("us-east-1", ACCOUNT, json(Map.of("Group", "pool"))));
        assertThrows(AwsException.class,
                () -> service.get(REGION, "444455556666", json(Map.of("Group", group.arn()))));
        assertThrows(AwsException.class,
                () -> service.get("us-east-1", ACCOUNT, json(Map.of("Group", group.arn()))));
        ResourceGroup other = service.create(REGION, "444455556666", createRequest("pool"));
        assertNotEquals(group.arn(), other.arn());
    }

    @Test
    void missingForeignAndInactiveReservationsReturnFailedWithoutMembership() throws Exception {
        create("pool");
        String active = reservation(1);
        String cancelled = reservation(2);
        reservations.get("cr-00000000000000002").setState("cancelled");
        String absent = active.replace("00000000000000001", "00000000000000003");
        String foreign = active.replace(ACCOUNT, "444455556666");
        Map<String, Object> result = add("pool", active, cancelled, absent, foreign);
        assertEquals(List.of(active), result.get("Succeeded"));
        List<?> failed = (List<?>) result.get("Failed");
        assertEquals(3, failed.size());
        assertTrue(failed.stream().allMatch(item -> ((Map<?, ?>) item).get("ErrorCode")
                .equals("ResourceGroupsValidationException")));
        assertEquals(1, ((List<?>) list("pool").get("ResourceIdentifiers")).size());
    }

    @ParameterizedTest
    @CsvSource({
            "arn:aws:ec2:us-east-1:111122223333:capacity-reservation/cr-00000000000000001,ValidationException",
            "arn:aws:ec2:eu-west-1:111122223333:instance/i-00000000000000001,ResourceArnValidationException",
            "arn:aws:lambda:eu-west-1:111122223333:function:missing,ResourceArnValidationException",
            "arn:aws:ec2:eu-west-1:111122223333:capacity-reservation/cr-invalid,ResourceGroupsValidationException"
    })
    void unsupportedReservationIdentityUsesTheAwsPerResourceFailure(String arn, String code) throws Exception {
        create("pool");
        Map<String, Object> result = add("pool", arn);
        List<?> failed = (List<?>) result.get("Failed");
        assertEquals(1, failed.size());
        assertEquals(code, ((Map<?, ?>) failed.getFirst()).get("ErrorCode"));
        assertEquals(List.of(), result.get("Succeeded"));
        assertEquals(List.of(), list("pool").get("ResourceIdentifiers"));
    }

    @Test
    void unexpectedLookupOrForeignRecordedOwnerDoesNotCommitEarlierSuccess() throws Exception {
        create("pool");
        String first = reservation(1);
        String second = reservation(2);
        reservations.get("cr-00000000000000002").setOwnerId("444455556666");
        assertThrows(AwsException.class, () -> add("pool", first, second));
        assertEquals(List.of(), list("pool").get("ResourceIdentifiers"));
        when(ec2.describeCapacityReservations(eq(REGION), eq(List.of("cr-00000000000000002")), anyMap()))
                .thenThrow(new AwsException("ServiceUnavailable", "not ready", 503));
        assertEquals(503, assertThrows(AwsException.class, () -> add("pool", first, second)).getHttpStatus());
        assertEquals(List.of(), list("pool").get("ResourceIdentifiers"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "{\"Name\":\"pool\"}", "{\"Name\":\"pool\",\"Configuration\":[]}",
            "{\"Name\":\"pool\",\"Configuration\":[{\"Type\":\"AWS::EC2::HostManagement\"}]}",
            "{\"Name\":\"pool\",\"ResourceQuery\":{}}"
    })
    void unsupportedOrMissingConfigurationFails(String body) throws Exception {
        assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT, mapper.readTree(body)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWSreserved", "aWsreserved", "bad/name", "bad name", ""})
    void invalidNamesRefuse(String name) throws Exception {
        assertThrows(AwsException.class, () -> create(name));
    }

    @Test
    void publicSdkNameAndDescriptionBoundsArePreserved() throws Exception {
        JsonNode request = mapper.readTree("{\"Name\":\"" + "p".repeat(300) + "\",\"Description\":\""
                + "a".repeat(1024) + "\",\"Configuration\":" + CONFIGURATION + "}");
        assertEquals(300, service.create(REGION, ACCOUNT, request).name().length());
        assertThrows(AwsException.class, () -> create("p".repeat(301)));
        assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT,
                mapper.readTree(request.toString().replace("a".repeat(1024), "a".repeat(1025)))));
        assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT,
                mapper.readTree(request.toString().replace("a".repeat(1024), "bad/description"))));
    }

    @Test
    void malformedConfigurationParametersAndMixedQueryRefuse() throws Exception {
        String valid = mapper.writeValueAsString(createRequest("pool"));
        for (String changed : List.of(
                valid.replace(ResourceGroupsService.RESOURCE_TYPE, "AWS::EC2::Instance"),
                valid.replace("allowed-resource-types", "unrecognized"),
                valid.replace("AWS::EC2::CapacityReservationPool", "AWS::ResourceGroups::Generic"),
                valid.replace("\"Name\":\"pool\"", "\"Name\":\"pool\",\"ResourceQuery\":{}"))) {
            assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT, mapper.readTree(changed)));
        }
    }

    @Test
    void invalidMembershipRequestsFailBeforeAnyChange() throws Exception {
        create("pool");
        String valid = reservation(1);
        assertThrows(AwsException.class, () -> add("pool"));
        assertThrows(AwsException.class, () -> add("pool", valid, "not-an-arn"));
        assertThrows(AwsException.class, () -> add("missing", valid));
        assertThrows(AwsException.class, () -> service.get(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "GroupName", "pool"))));
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "MaxResults", true))));
        assertEquals(List.of(), list("pool").get("ResourceIdentifiers"));
        verifyNoInteractions(ec2);
    }

    @Test
    void ignoresUnknownFieldsAndChecksModeledTagsAndMetadata() throws Exception {
        JsonNode request = createRequest("pool");
        ((ObjectNode) request).put("Group", "ignored");
        ((ObjectNode) request).set("Tags", json(Map.of("équipe /", "a:b@c")));
        ResourceGroup group = service.create(REGION, ACCOUNT, request);
        assertEquals(Map.of("équipe /", "a:b@c"), group.tags());
        assertEquals("pool", group.name());
        for (String field : List.of("Owner", "DisplayName", "Criticality")) {
            JsonNode modeled = createRequest("metadata-" + field);
            ((ObjectNode) modeled).set(field,
                    json(field.equals("Criticality") ? 7 : "valid / +"));
            assertEquals("Unsupported parameter for this operation",
                    assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT, modeled)).getMessage());
        }
        for (Map<String, String> tags : List.of(Map.of("bad$", "value"), Map.of("key", "bad$"))) {
            JsonNode invalid = createRequest("bad-tags");
            ((ObjectNode) invalid).set("Tags", json(tags));
            assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT, invalid));
        }
        for (int value : List.of(0, 11)) {
            JsonNode invalid = createRequest("bad-criticality");
            ((ObjectNode) invalid).put("Criticality", value);
            assertThrows(AwsException.class, () -> service.create(REGION, ACCOUNT, invalid));
        }
        assertEquals("pool", service.get(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "Unknown", "ignored", "DisplayName", Map.of()))).name());
    }

    @Test
    void paginationSurvivesRemovalOfTheLastItemOnThePreviousPage() throws Exception {
        ResourceGroup group = create("pool");
        String first = reservation(1);
        String second = reservation(2);
        String third = reservation(3);
        add("pool", first, second, third);
        String token = (String) service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "MaxResults", 2))).get("NextToken");
        groups.putForAccount(ACCOUNT, REGION + "::pool", group.withMembers(List.of(first, third)));
        Map<String, Object> page = service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "NextToken", token)));
        assertEquals(List.of(Map.of("ResourceArn", third, "ResourceType", ResourceGroupsService.RESOURCE_TYPE)),
                page.get("ResourceIdentifiers"));
        assertFalse(page.containsKey("NextToken"));
    }

    @Test
    void paginationKeepsSnapshotScopeReplayAndGroupGeneration() throws Exception {
        create("pool");
        create("other");
        String first = reservation(1);
        String second = reservation(2);
        add("pool", first, second);
        Map<String, Object> firstPage = service.list(REGION, ACCOUNT, json(Map.of("Group", "pool", "MaxResults", 1)));
        String token = (String) firstPage.get("NextToken");
        assertTrue(token.matches("[a-zA-Z0-9+/]*={0,2}"));
        assertDoesNotThrow(() -> Base64.getDecoder().decode(token));
        JsonNode continued = json(Map.of("Group", "pool", "NextToken", token));
        add("pool", reservation(3));
        Map<String, Object> page = service.list(REGION, ACCOUNT, continued);
        assertEquals(List.of(Map.of("ResourceArn", second, "ResourceType", ResourceGroupsService.RESOURCE_TYPE)),
                page.get("ResourceIdentifiers"));
        assertEquals(page, service.list(REGION, ACCOUNT, continued));
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT,
                json(Map.of("Group", "other", "NextToken", token))));
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "NextToken", token, "Filters",
                        List.of(Map.of("Name", "resource-type", "Values", List.of(ResourceGroupsService.RESOURCE_TYPE)))))));
        service.delete(REGION, ACCOUNT, json(Map.of("Group", "pool")));
        create("pool");
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT, continued));
    }

    @Test
    void paginationHasBoundedExpiryAndAllowsFreshTraversal() throws Exception {
        create("pool");
        add("pool", reservation(1), reservation(2));
        JsonNode request = json(Map.of("Group", "pool", "MaxResults", 1));
        List<String> tokens = new ArrayList<>();
        for (int index = 0; index <= ResourceGroupsService.MAX_PAGES; index++) {
            tokens.add((String) service.list(REGION, ACCOUNT, request).get("NextToken"));
        }
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "NextToken", tokens.getFirst()))));
        when(clock.millis()).thenReturn(ResourceGroupsService.PAGE_IDLE_MILLIS + 1001);
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "NextToken", tokens.getLast()))));
        assertNotNull(service.list(REGION, ACCOUNT, request).get("NextToken"));
    }

    @Test
    void clearDropsGroupsAndTokens() throws Exception {
        create("pool");
        add("pool", reservation(1), reservation(2));
        String token = (String) service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "MaxResults", 1))).get("NextToken");
        service.clear();
        assertThrows(AwsException.class, () -> list("pool"));
        create("pool");
        assertThrows(AwsException.class, () -> service.list(REGION, ACCOUNT,
                json(Map.of("Group", "pool", "NextToken", token))));
    }
}
