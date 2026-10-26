package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EcrScanningConfigurationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();
    private EcrService service;
    private EcrRegistryManager registry;
    private EcrJsonHandler handler;

    @BeforeEach
    void setUp() {
        registry = mock(EcrRegistryManager.class);
        when(registry.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenReturn("localhost:4566/scanning-test");
        service = new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), registry, mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
        handler = new EcrJsonHandler(service, mapper);
    }

    @Test
    void missingRepositoryDoesNotDiscardConfigurationsBeforeOrAfterIt() {
        create("scan-on", true, null, REGION);
        create("scan-off", false, null, REGION);

        JsonNode response = batch(List.of("scan-on", "missing", "scan-off"), REGION);

        JsonNode configs = response.path("scanningConfigurations");
        assertEquals(2, configs.size());
        assertEquals("scan-on", configs.get(0).path("repositoryName").asText());
        assertTrue(configs.get(0).path("scanOnPush").asBoolean());
        assertEquals("SCAN_ON_PUSH", configs.get(0).path("scanFrequency").asText());
        assertEquals("scan-off", configs.get(1).path("repositoryName").asText());
        assertFalse(configs.get(1).path("scanOnPush").asBoolean());
        assertEquals("MANUAL", configs.get(1).path("scanFrequency").asText());
        assertTrue(configs.get(0).path("appliedScanFilters").isEmpty());
        JsonNode failures = response.path("failures");
        assertEquals(1, failures.size());
        assertFailure(failures.get(0), "missing");
    }

    @Test
    void allMissingRepositoriesReturnIndividualFailures() {
        JsonNode response = batch(List.of("missing-first", "missing-second"), REGION);

        assertTrue(response.path("scanningConfigurations").isEmpty());
        assertEquals(2, response.path("failures").size());
        assertFailure(response.path("failures").get(0), "missing-first");
        assertFailure(response.path("failures").get(1), "missing-second");
    }

    @Test
    void allExistingRepositoriesReturnNoFailures() {
        create("present", false, null, REGION);

        JsonNode response = batch(List.of("present"), REGION);

        assertEquals(1, response.path("scanningConfigurations").size());
        assertTrue(response.path("failures").isEmpty());
    }

    @Test
    void repositoryInAnotherAccountOrRegionIsReportedMissing() {
        create("other-region", true, null, "us-west-2");
        create("other-account", true, "111111111111", REGION);

        JsonNode response = batch(List.of("other-region", "other-account"), REGION);

        assertTrue(response.path("scanningConfigurations").isEmpty());
        assertEquals(2, response.path("failures").size());
        assertFailure(response.path("failures").get(0), "other-region");
        assertFailure(response.path("failures").get(1), "other-account");
    }

    @Test
    void unrelatedErrorsAreNotReportedAsMissingRepositories() {
        create("present", false, null, REGION);
        AwsException failure = new AwsException("ServerException", "lookup failed", 500);
        when(registry.getRepositoryUri(anyString(), anyString(), anyString())).thenThrow(failure);

        assertSame(failure, assertThrows(AwsException.class,
                () -> batch(List.of("missing", "present"), REGION)));
    }

    @Test
    void describeRepositoriesStillFailsTheWholeRequest() {
        create("present", false, null, REGION);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.describeRepositories(List.of("present", "missing"), null, REGION));

        assertEquals("RepositoryNotFoundException", failure.getErrorCode());
    }

    private void create(String name, boolean scanOnPush, String account, String region) {
        service.createRepository(name, account, null, scanOnPush, null, null, Map.of(), region);
    }

    private JsonNode batch(List<String> names, String region) {
        try (Response response = handler.handle("BatchGetRepositoryScanningConfiguration",
                mapper.valueToTree(Map.of("repositoryNames", names)), region)) {
            assertEquals(200, response.getStatus());
            return (JsonNode) response.getEntity();
        }
    }

    private static void assertFailure(JsonNode failure, String name) {
        assertEquals(name, failure.path("repositoryName").asText());
        assertEquals("REPOSITORY_NOT_FOUND", failure.path("failureCode").asText());
        assertTrue(failure.path("failureReason").asText().contains(name));
    }
}
