package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bedrock.model.EnforcedGuardrailConfiguration;
import io.floci.aws.services.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class BedrockEnforcedGuardrailIsolationTest {

    private static final String REGION = "us-east-1";
    private static final String CALLER = "arn:aws:iam::000000000000:user/first";

    private final ObjectMapper mapper = new ObjectMapper();
    private BedrockService service;
    private String guardrail;

    @BeforeEach
    void prepareGuardrail() {
        service = new BedrockService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), mapper, mock(KmsService.class));
        guardrail = BedrockEnforcedGuardrailFixtures.publish(service, REGION,
                BedrockEnforcedGuardrailFixtures.guardrailRequest(mapper, "isolation", "blocked", "blocked")).getGuardrailId();
    }

    @Test
    void mutatingTheRequestAfterPutCannotChangeTheSavedConfiguration() {
        ObjectNode request = request();
        service.putEnforcedGuardrailConfiguration(request, CALLER, REGION);
        ObjectNode inference = (ObjectNode) request.get("guardrailInferenceConfig");
        ((ObjectNode) inference.get("selectiveContentGuarding")).put("system", "COMPREHENSIVE");
        ((ObjectNode) inference.get("modelEnforcement")).putArray("includedModels").add("changed");
        assertSavedConfiguration();
    }

    @Test
    void mutatingThePutResponseCannotChangeTheSavedConfiguration() {
        EnforcedGuardrailConfiguration returned = service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION);
        returned.setUpdatedBy("changed");
        ((ObjectNode) returned.getSelectiveContentGuarding()).put("system", "COMPREHENSIVE");
        assertSavedConfiguration();
    }

    @Test
    void mutatingAListItemCannotChangeTheSavedConfiguration() {
        service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION);
        EnforcedGuardrailConfiguration returned = saved();
        returned.setGuardrailVersion("9");
        ((ObjectNode) returned.getModelEnforcement()).putArray("includedModels").add("changed");
        assertSavedConfiguration();
    }

    @Test
    void concurrentUpdatesKeepEachReturnedCallerSnapshot() throws Exception {
        String id = service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION).getConfigId();
        List<EnforcedGuardrailConfiguration> returned = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<EnforcedGuardrailConfiguration>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String caller = CALLER + i;
                futures.add(executor.submit(() -> service.putEnforcedGuardrailConfiguration(
                        request().put("configId", id), caller, REGION)));
            }
            for (Future<EnforcedGuardrailConfiguration> future : futures) {
                returned.add(future.get());
            }
        }
        for (int i = 0; i < returned.size(); i++) {
            assertEquals(CALLER + i, returned.get(i).getUpdatedBy());
            assertEquals(id, returned.get(i).getConfigId());
        }
    }

    @Test
    void aRejectedReplacementLeavesTheStoredConfigurationIntact() {
        String id = service.putEnforcedGuardrailConfiguration(request(), CALLER, REGION).getConfigId();
        ObjectNode invalid = request().put("configId", id);
        ((ObjectNode) invalid.get("guardrailInferenceConfig")).put("guardrailVersion", "DRAFT");
        AwsException error = assertThrows(AwsException.class,
                () -> service.putEnforcedGuardrailConfiguration(invalid, "changed", REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertSavedConfiguration();
    }

    private void assertSavedConfiguration() {
        EnforcedGuardrailConfiguration stored = saved();
        assertEquals("1", stored.getGuardrailVersion());
        assertEquals(CALLER, stored.getUpdatedBy());
        assertEquals("SELECTIVE", stored.getSelectiveContentGuarding().path("system").asText());
        assertEquals("ALL", stored.getModelEnforcement().path("includedModels").get(0).asText());
    }

    private EnforcedGuardrailConfiguration saved() {
        return service.listEnforcedGuardrailConfigurations(null, REGION).items().getFirst();
    }

    private ObjectNode request() {
        return BedrockEnforcedGuardrailFixtures.selectiveRequest(mapper, guardrail, "1");
    }
}
