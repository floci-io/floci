package io.github.hectorvent.floci.services.dlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.dlm.model.LifecyclePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DlmServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/dlm";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AccountAwareStorageBackend<LifecyclePolicy> policies;
    private DlmService service;

    @BeforeEach
    void setUp() {
        policies = AccountAwareStorageBackend.inMemory("000000000000");
        service = new DlmService(policies);
    }

    @Test
    void lifecyclePreservesNestedPolicyDetailsAndOmittedUpdateMembers() throws Exception {
        LifecyclePolicy created = service.createLifecyclePolicy(customPolicy("daily", "ENABLED"), REGION);

        assertTrue(created.getPolicyId().matches("policy-[a-f0-9]+"));
        assertEquals("VOLUME", created.getPolicyDetails().path("ResourceTypes").get(0).asText());
        assertEquals(24, created.getPolicyDetails().path("Schedules").get(0)
                .path("CreateRule").path("Interval").asInt());
        assertEquals("arn:aws:dlm:us-east-1:000000000000:policy/" + created.getPolicyId(),
                created.getPolicyArn());
        assertNotNull(created.getDateCreated());

        JsonNode update = objectMapper.readTree("""
                {"Description":"weekly","State":"DISABLED","PolicyDetails":{
                  "PolicyType":"EBS_SNAPSHOT_MANAGEMENT",
                  "ResourceTypes":["VOLUME"],
                  "TargetTags":[{"Key":"backup","Value":"true"}],
                  "Schedules":[{"Name":"weekly","CreateRule":{"Interval":168,"IntervalUnit":"HOURS"},
                    "RetainRule":{"Count":4},"TagsToAdd":[{"Key":"managed","Value":"dlm"}]}]
                }}
                """);
        service.updateLifecyclePolicy(created.getPolicyId(), update, REGION);

        LifecyclePolicy updated = service.getLifecyclePolicy(created.getPolicyId(), REGION);
        assertEquals("weekly", updated.getDescription());
        assertEquals("DISABLED", updated.getState());
        assertEquals(ROLE, updated.getExecutionRoleArn());
        assertEquals(168, updated.getPolicyDetails().path("Schedules").get(0)
                .path("CreateRule").path("Interval").asInt());
        assertEquals("test", updated.getTags().get("env"));

        service.deleteLifecyclePolicy(created.getPolicyId(), REGION);
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getLifecyclePolicy(created.getPolicyId(), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void listSupportsAllDocumentedFilters() throws Exception {
        LifecyclePolicy daily = service.createLifecyclePolicy(customPolicy("daily", "ENABLED"), REGION);
        service.createLifecyclePolicy(objectMapper.readTree("""
                {"ExecutionRoleArn":"%s","Description":"images","State":"DISABLED",
                 "PolicyDetails":{"PolicyType":"IMAGE_MANAGEMENT","ResourceTypes":["INSTANCE"],
                   "TargetTags":[{"Key":"image","Value":"true"}],
                   "Schedules":[{"TagsToAdd":[{"Key":"managed","Value":"ami"}]}]}}
                """.formatted(ROLE)), REGION);
        service.createLifecyclePolicy(objectMapper.readTree("""
                {"ExecutionRoleArn":"%s","Description":"defaults","State":"ENABLED",
                 "DefaultPolicy":"VOLUME","CreateInterval":1,"RetainInterval":7}
                """.formatted(ROLE)), REGION);

        assertEquals(List.of(daily.getPolicyId()), service.getLifecyclePolicies(REGION,
                List.of(daily.getPolicyId()), null, List.of(), List.of(), List.of(), null).stream()
                .map(LifecyclePolicy::getPolicyId).toList());
        assertEquals(2, service.getLifecyclePolicies(REGION, List.of(), "ENABLED",
                List.of(), List.of(), List.of(), null).size());
        assertEquals(1, service.getLifecyclePolicies(REGION, List.of(), null,
                List.of("INSTANCE"), List.of(), List.of(), null).size());
        assertEquals(1, service.getLifecyclePolicies(REGION, List.of(), null,
                List.of(), List.of("backup=true"), List.of(), null).size());
        assertEquals(1, service.getLifecyclePolicies(REGION, List.of(), null,
                List.of(), List.of(), List.of("managed=dlm"), null).size());
        assertEquals(1, service.getLifecyclePolicies(REGION, List.of(), null,
                List.of(), List.of(), List.of(), "VOLUME").size());
        assertEquals(1, service.getLifecyclePolicies(REGION, List.of(), null,
                List.of(), List.of(), List.of(), "ALL").size());
    }

    @Test
    void tagsRoundTripThroughTheSharedRestHandlerContract() throws Exception {
        LifecyclePolicy policy = service.createLifecyclePolicy(customPolicy("daily", "ENABLED"), REGION);

        assertEquals("Tags", service.tagsBodyKey());
        assertEquals("InvalidRequestException", service.tagValidationErrorCode());
        assertEquals(200, service.tagResourceSuccessStatus());
        service.tagResource(REGION, policy.getPolicyArn(), Map.of("owner", "platform"));
        assertEquals(Map.of("env", "test", "owner", "platform"),
                service.listTags(REGION, policy.getPolicyArn()));

        service.untagResource(REGION, policy.getPolicyArn(), List.of("env"));
        assertEquals(Map.of("owner", "platform"), service.listTags(REGION, policy.getPolicyArn()));
    }

    @Test
    void invalidRequestsDoNotMutateStoredState() throws Exception {
        LifecyclePolicy policy = service.createLifecyclePolicy(customPolicy("daily", "ENABLED"), REGION);

        AwsException badDescription = assertThrows(AwsException.class, () ->
                service.updateLifecyclePolicy(policy.getPolicyId(),
                        objectMapper.readTree("{\"Description\":\"not.allowed\"}"), REGION));
        assertEquals("InvalidRequestException", badDescription.getErrorCode());
        assertEquals("daily", service.getLifecyclePolicy(policy.getPolicyId(), REGION).getDescription());

        AwsException changedType = assertThrows(AwsException.class, () ->
                service.updateLifecyclePolicy(policy.getPolicyId(), objectMapper.readTree("""
                        {"PolicyDetails":{"PolicyType":"IMAGE_MANAGEMENT","ResourceTypes":["VOLUME"]}}
                        """), REGION));
        assertEquals("InvalidRequestException", changedType.getErrorCode());

        AwsException partiallyInvalid = assertThrows(AwsException.class, () ->
                service.updateLifecyclePolicy(policy.getPolicyId(), objectMapper.readTree("""
                        {"Description":"changed","State":"ERROR"}
                        """), REGION));
        assertEquals("InvalidRequestException", partiallyInvalid.getErrorCode());
        assertEquals("daily", service.getLifecyclePolicy(policy.getPolicyId(), REGION).getDescription());

        AwsException badState = assertThrows(AwsException.class, () ->
                service.createLifecyclePolicy(objectMapper.readTree("""
                        {"ExecutionRoleArn":"%s","Description":"bad","State":"ERROR"}
                        """.formatted(ROLE)), REGION));
        assertEquals("InvalidRequestException", badState.getErrorCode());
    }

    @Test
    void createAllowsEmptyTagsAndSupportedArnPartitions() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"ExecutionRoleArn":"arn:aws-eusc:iam::000000000000:role/dlm",
                 "Description":"daily","State":"ENABLED","Tags":{}}
                """);

        LifecyclePolicy policy = service.createLifecyclePolicy(request, REGION);

        assertTrue(policy.getTags().isEmpty());
        assertEquals("arn:aws-eusc:iam::000000000000:role/dlm", policy.getExecutionRoleArn());
    }

    @Test
    void listRejectsMultipleResourceTypes() throws Exception {
        service.createLifecyclePolicy(customPolicy("daily", "ENABLED"), REGION);

        AwsException invalid = assertThrows(AwsException.class, () -> service.getLifecyclePolicies(REGION,
                List.of(), null, List.of("VOLUME", "INSTANCE"), List.of(), List.of(), null));

        assertEquals("InvalidRequestException", invalid.getErrorCode());
    }

    @Test
    void regionScopingAndResetAreAppliedByTheStorageKey() throws Exception {
        LifecyclePolicy policy = service.createLifecyclePolicy(customPolicy("daily", "ENABLED"), REGION);

        assertTrue(service.getLifecyclePolicies("eu-west-1", List.of(), null,
                List.of(), List.of(), List.of(), null).isEmpty());
        assertThrows(AwsException.class,
                () -> service.getLifecyclePolicy(policy.getPolicyId(), "eu-west-1"));

        service.clear();
        assertTrue(policies.keys().isEmpty());
        assertFalse(service.getLifecyclePolicies(REGION, List.of(), null,
                List.of(), List.of(), List.of(), null).stream().findAny().isPresent());
    }

    private JsonNode customPolicy(String description, String state) throws Exception {
        return objectMapper.readTree("""
                {"ExecutionRoleArn":"%s","Description":"%s","State":"%s","Tags":{"env":"test"},
                 "PolicyDetails":{"PolicyType":"EBS_SNAPSHOT_MANAGEMENT","ResourceTypes":["VOLUME"],
                   "TargetTags":[{"Key":"backup","Value":"true"}],
                   "Schedules":[{"Name":"daily","CreateRule":{"Interval":24,"IntervalUnit":"HOURS"},
                     "RetainRule":{"Count":7},"TagsToAdd":[{"Key":"managed","Value":"dlm"}]}]}}
                """.formatted(ROLE, description, state));
    }
}
