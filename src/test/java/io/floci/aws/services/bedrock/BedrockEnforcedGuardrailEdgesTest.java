package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrock.model.EnforcedGuardrailConfiguration;
import io.floci.aws.services.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockEnforcedGuardrailEdgesTest {

    private static final String REGION = "us-east-1";
    private static final String CALLER = "arn:aws:iam::000000000000:root";

    private final ObjectMapper mapper = new ObjectMapper();
    private BedrockService service;
    private String guardrail;

    @BeforeEach
    void prepareGuardrail() {
        service = new BedrockService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), mapper, mock(KmsService.class));
        guardrail = BedrockEnforcedGuardrailFixtures.publish(service, REGION,
                BedrockEnforcedGuardrailFixtures.guardrailRequest(mapper, "edges", "blocked", "blocked")).getGuardrailId();
    }

    @Test
    void paginationContinuesAfterDeletingTheCursorRecord() {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            expected.add(service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION).getConfigId());
        }
        PaginatedResult<EnforcedGuardrailConfiguration> first = service.listEnforcedGuardrailConfigurations(null, REGION);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        String deleted = first.items().getFirst().getConfigId();
        expected.remove(deleted);
        service.deleteEnforcedGuardrailConfiguration(deleted, REGION);
        Set<String> seen = new HashSet<>();
        String token = first.nextToken();
        for (int i = 0; i < 2; i++) {
            PaginatedResult<EnforcedGuardrailConfiguration> page = service.listEnforcedGuardrailConfigurations(token, REGION);
            assertEquals(1, page.items().size());
            assertTrue(seen.add(page.items().getFirst().getConfigId()));
            token = page.nextToken();
        }
        assertNull(token);
        assertEquals(expected, seen);
        assertFalse(seen.contains(deleted));
    }

    @Test
    void malformedCursorIsRejected() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listEnforcedGuardrailConfigurations("!!!!", REGION));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void invalidNumericalVersionBoundsAreRejectedWithoutWriting() {
        for (String version : new String[]{"0", "01", "100000000"}) {
            ObjectNode request = request();
            ((ObjectNode) request.get("guardrailInferenceConfig")).put("guardrailVersion", version);
            AwsException error = assertThrows(AwsException.class,
                    () -> service.putEnforcedGuardrailConfiguration(request, CALLER, REGION));
            assertEquals("ValidationException", error.getErrorCode());
        }
        assertTrue(service.listEnforcedGuardrailConfigurations(null, REGION).items().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectiveContentGuardingRejectsNonObjectValues(boolean array) {
        ObjectNode request = request();
        ObjectNode config = (ObjectNode) request.get("guardrailInferenceConfig");
        if (array) {
            config.putArray("selectiveContentGuarding").add("SELECTIVE");
        } else {
            config.put("selectiveContentGuarding", "SELECTIVE");
        }
        assertInvalidAndUnstored(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"includedModels", "excludedModels"})
    void modelListsRejectNonStringMembers(String member) {
        ObjectNode request = request();
        ObjectNode config = (ObjectNode) request.get("guardrailInferenceConfig");
        ObjectNode enforcement = config.putObject("modelEnforcement");
        enforcement.putArray("includedModels").add("ALL");
        enforcement.putArray("excludedModels");
        enforcement.putArray(member).add(42);
        assertInvalidAndUnstored(request);
    }

    @ParameterizedTest
    @CsvSource({
            "partition,false", "region,false", "account,false", "service,false", "resource,false",
            "partition,true", "region,true", "account,true", "service,true", "resource,true"
    })
    void mismatchedGuardrailArnIsRejectedWithoutMutation(String component, boolean replace) {
        String arn = service.getGuardrail(guardrail, "1", REGION).getGuardrailArn();
        String mismatchedArn = switch (component) {
            case "partition" -> arn.replace("arn:aws:", "arn:aws-cn:");
            case "region" -> arn.replace(":us-east-1:", ":us-west-2:");
            case "account" -> arn.replace(":000000000000:", ":111111111111:");
            case "service" -> arn.replace(":bedrock:", ":sagemaker:");
            case "resource" -> arn.replace(":guardrail/", ":guardrail-profile/");
            default -> throw new IllegalArgumentException(component);
        };
        ObjectNode invalid = request();
        String configId = replace
                ? service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION).getConfigId() : null;
        if (replace) {
            invalid.put("configId", configId);
        }
        ((ObjectNode) invalid.get("guardrailInferenceConfig")).put("guardrailIdentifier", mismatchedArn);
        ObjectNode originalRequest = invalid.deepCopy();
        EnforcedGuardrailConfiguration before = configId == null ? null
                : service.listEnforcedGuardrailConfigurations(null, REGION).items().getFirst();

        AwsException error = assertThrows(AwsException.class,
                () -> service.putEnforcedGuardrailConfiguration(invalid, "changed", REGION));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals(originalRequest, invalid);
        if (replace) {
            EnforcedGuardrailConfiguration after =
                    service.listEnforcedGuardrailConfigurations(null, REGION).items().getFirst();
            assertEquals(before.getConfigId(), after.getConfigId());
            assertEquals(before.getGuardrailId(), after.getGuardrailId());
            assertEquals(before.getGuardrailArn(), after.getGuardrailArn());
            assertEquals(before.getGuardrailVersion(), after.getGuardrailVersion());
            assertEquals(before.getSelectiveContentGuarding(), after.getSelectiveContentGuarding());
            assertEquals(before.getModelEnforcement(), after.getModelEnforcement());
            assertEquals(before.getCreatedAt(), after.getCreatedAt());
            assertEquals(before.getCreatedBy(), after.getCreatedBy());
            assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
            assertEquals(before.getUpdatedBy(), after.getUpdatedBy());
        } else {
            assertTrue(service.listEnforcedGuardrailConfigurations(null, REGION).items().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactGuardrailArnAndIdRemainAccepted(boolean useArn) {
        String arn = service.getGuardrail(guardrail, "1", REGION).getGuardrailArn();
        ObjectNode valid = request();
        ((ObjectNode) valid.get("guardrailInferenceConfig")).put("guardrailIdentifier", useArn ? arn : guardrail);
        EnforcedGuardrailConfiguration created = service.putEnforcedGuardrailConfiguration(valid, CALLER, REGION);
        assertEquals(guardrail, created.getGuardrailId());
        assertEquals(arn, created.getGuardrailArn());
        valid.put("configId", created.getConfigId());
        EnforcedGuardrailConfiguration replaced = service.putEnforcedGuardrailConfiguration(valid, "changed", REGION);
        assertEquals(created.getConfigId(), replaced.getConfigId());
        assertEquals(arn, replaced.getGuardrailArn());
        assertEquals("changed", replaced.getUpdatedBy());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configuredGuardrailCannotBeDeletedWithoutMutation(boolean wholeGuardrail) {
        EnforcedGuardrailConfiguration configuration =
                service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION);
        String version = wholeGuardrail ? null : "1";

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteGuardrail(guardrail, version, REGION));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("The guardrail cannot be deleted because it is associated with an account-level "
                + "or organization-level default guardrail configuration.", error.getMessage());
        assertEquals(guardrail, service.getGuardrail(guardrail, "DRAFT", REGION).getGuardrailId());
        assertEquals(guardrail, service.getGuardrail(guardrail, "1", REGION).getGuardrailId());
        assertEquals(configuration.getConfigId(),
                service.listEnforcedGuardrailConfigurations(null, REGION).items().getFirst().getConfigId());
    }

    @Test
    void unrelatedVersionCanBeDeletedWhileConfiguredVersionRemains() {
        EnforcedGuardrailConfiguration configuration =
                service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION);
        service.createGuardrailVersion(guardrail, null, REGION);

        service.deleteGuardrail(guardrail, "2", REGION);

        assertEquals(guardrail, service.getGuardrail(guardrail, "1", REGION).getGuardrailId());
        assertEquals(configuration.getConfigId(),
                service.listEnforcedGuardrailConfigurations(null, REGION).items().getFirst().getConfigId());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getGuardrail(guardrail, "2", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void guardrailCanBeDeletedAfterItsConfigurationIsRemoved() {
        String configId = service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION).getConfigId();
        service.deleteEnforcedGuardrailConfiguration(configId, REGION);

        service.deleteGuardrail(guardrail, null, REGION);

        assertTrue(service.listEnforcedGuardrailConfigurations(null, REGION).items().isEmpty());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getGuardrail(guardrail, "1", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void deletingUnknownConfigurationUsesTheModeledMessageWithoutMutation() {
        String configId = service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION).getConfigId();

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteEnforcedGuardrailConfiguration("missingconfiguration", REGION));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals("No default guardrail configuration found for the specified config identifier.",
                error.getMessage());
        assertEquals(configId,
                service.listEnforcedGuardrailConfigurations(null, REGION).items().getFirst().getConfigId());
    }

    private void assertInvalidAndUnstored(ObjectNode request) {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putEnforcedGuardrailConfiguration(request, CALLER, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(service.listEnforcedGuardrailConfigurations(null, REGION).items().isEmpty());
    }

    private ObjectNode request() {
        return BedrockEnforcedGuardrailFixtures.request(mapper, guardrail, "1");
    }
}
