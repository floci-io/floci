package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.bedrock.model.EnforcedGuardrailConfiguration;
import io.floci.aws.services.bedrock.model.Guardrail;
import io.floci.aws.services.kms.KmsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockEnforcedGuardrailWalTest {

    private static final String ACCOUNT = "741623800038";
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "eu-west-1";
    private static final String CALLER = "arn:aws:iam::" + ACCOUNT + ":user/first";
    private static final String OTHER_CALLER = "arn:aws:iam::" + ACCOUNT + ":user/second";
    private static final String SNAPSHOT = "bedrock-enforced-guardrail-configurations.json";
    private static final String LOG = "bedrock-enforced-guardrail-configurations.wal";

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final InMemoryStorage<String, Guardrail> guardrails = new InMemoryStorage<>();

    @Test
    void gracefulReopenPreservesReplacementMetadataAndDeletion() {
        WalStorage<String, EnforcedGuardrailConfiguration> raw = open(directory);
        String id;
        Instant created;
        try {
            BedrockService service = service(raw, ACCOUNT);
            String guardrail = versionedGuardrail(service);
            EnforcedGuardrailConfiguration configuration = service.putEnforcedGuardrailConfiguration(
                    request(guardrail, "1"), CALLER, REGION);
            id = configuration.getConfigId();
            created = configuration.getCreatedAt();
            service.createGuardrailVersion(guardrail, null, REGION);
            service.putEnforcedGuardrailConfiguration(request(guardrail, "2").put("configId", id), OTHER_CALLER, REGION);
        } finally {
            raw.shutdown();
        }
        raw = open(directory);
        try {
            BedrockService service = service(raw, ACCOUNT);
            EnforcedGuardrailConfiguration recovered = configurations(service, REGION).getFirst();
            assertEquals(id, recovered.getConfigId());
            assertEquals("2", recovered.getGuardrailVersion());
            assertEquals(created, recovered.getCreatedAt());
            assertEquals(CALLER, recovered.getCreatedBy());
            assertEquals(OTHER_CALLER, recovered.getUpdatedBy());
            assertEquals("ALL", recovered.getModelEnforcement().path("includedModels").get(0).asText());
            assertEquals("SELECTIVE", recovered.getSelectiveContentGuarding().path("system").asText());
            service.deleteEnforcedGuardrailConfiguration(id, REGION);
        } finally {
            raw.shutdown();
        }
        raw = open(directory);
        try {
            assertTrue(configurations(service(raw, ACCOUNT), REGION).isEmpty());
            assertFalse(raw.get(ACCOUNT + "/" + REGION + "::" + id).isPresent());
        } finally {
            raw.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void copiedSnapshotAndLogRecoverUpdatesAndDeletesWithoutCompactingTheSource(boolean snapshotFirst) throws Exception {
        Path recoveredDirectory = directory.resolve("recovered");
        Files.createDirectory(recoveredDirectory);
        WalStorage<String, EnforcedGuardrailConfiguration> raw = open(directory);
        String retained;
        String deleted;
        String otherRegion;
        String guardrail;
        try {
            BedrockService service = service(raw, ACCOUNT);
            guardrail = versionedGuardrail(service);
            retained = service.putEnforcedGuardrailConfiguration(request(guardrail, "1"), CALLER, REGION).getConfigId();
            deleted = service.putEnforcedGuardrailConfiguration(request(guardrail, "1"), CALLER, REGION).getConfigId();
            if (snapshotFirst) {
                raw.flush();
            }
            service.createGuardrailVersion(guardrail, null, REGION);
            service.putEnforcedGuardrailConfiguration(request(guardrail, "2").put("configId", retained), OTHER_CALLER, REGION);
            service.deleteEnforcedGuardrailConfiguration(deleted, REGION);
            EnforcedGuardrailConfiguration foreign = new EnforcedGuardrailConfiguration();
            foreign.setConfigId("foreign1");
            raw.put("111122223333/" + REGION + "::foreign1", foreign);
            service.createGuardrail(requestForGuardrail("west"), OTHER_REGION);
            Guardrail west = service.listGuardrails(null, null, null, OTHER_REGION).items().getFirst();
            service.createGuardrailVersion(west.getGuardrailId(), null, OTHER_REGION);
            otherRegion = service.putEnforcedGuardrailConfiguration(request(west.getGuardrailId(), "1"), CALLER,
                    OTHER_REGION).getConfigId();
            for (String name : List.of(SNAPSHOT, LOG)) {
                Path source = directory.resolve(name);
                if (Files.exists(source)) {
                    Files.copy(source, recoveredDirectory.resolve(name));
                }
            }
            assertTrue(Files.size(recoveredDirectory.resolve(LOG)) > 0);
        } finally {
            raw.shutdown();
        }
        raw = open(recoveredDirectory);
        try {
            BedrockService service = service(raw, ACCOUNT);
            List<EnforcedGuardrailConfiguration> east = configurations(service, REGION);
            assertEquals(1, east.size());
            assertEquals(retained, east.getFirst().getConfigId());
            assertEquals("2", east.getFirst().getGuardrailVersion());
            assertEquals(OTHER_CALLER, east.getFirst().getUpdatedBy());
            assertEquals(otherRegion, configurations(service, OTHER_REGION).getFirst().getConfigId());
            assertEquals("foreign1", configurations(service(raw, "111122223333"), REGION).getFirst().getConfigId());
            assertFalse(raw.get(ACCOUNT + "/" + REGION + "::" + deleted).isPresent());
        } finally {
            raw.shutdown();
        }
    }

    @Test
    void originalActJsonSnapshotKeepsItsIdentifiersAndCanBeDeletedAcrossReopen() throws Exception {
        Files.writeString(directory.resolve(SNAPSHOT), """
                {"741623800038/us-east-1::legacy123": {
                  "configId":"legacy123", "guardrailId":"guardrail123",
                  "guardrailArn":"arn:aws:bedrock:us-east-1:741623800038:guardrail/guardrail123",
                  "guardrailVersion":"3", "selectiveContentGuarding":{"system":"SELECTIVE"},
                  "modelEnforcement":{"includedModels":["ALL"],"excludedModels":[]},
                  "createdAt":"2026-01-01T00:00:00Z", "createdBy":"arn:aws:iam::741623800038:user/first",
                  "updatedAt":"2026-01-02T00:00:00Z", "updatedBy":"arn:aws:iam::741623800038:user/second"
                }}
                """);
        WalStorage<String, EnforcedGuardrailConfiguration> raw = open(directory);
        try {
            BedrockService service = service(raw, ACCOUNT);
            EnforcedGuardrailConfiguration old = configurations(service, REGION).getFirst();
            assertEquals("legacy123", old.getConfigId());
            assertEquals("guardrail123", old.getGuardrailId());
            assertEquals("3", old.getGuardrailVersion());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), old.getCreatedAt());
            assertEquals(CALLER, old.getCreatedBy());
            assertEquals(OTHER_CALLER, old.getUpdatedBy());
            assertTrue(configurations(service, OTHER_REGION).isEmpty());
            assertTrue(configurations(service(raw, "111122223333"), REGION).isEmpty());
            service.deleteEnforcedGuardrailConfiguration("legacy123", REGION);
        } finally {
            raw.shutdown();
        }
        raw = open(directory);
        try {
            assertTrue(configurations(service(raw, ACCOUNT), REGION).isEmpty());
        } finally {
            raw.shutdown();
        }
    }

    private WalStorage<String, EnforcedGuardrailConfiguration> open(Path path) {
        WalStorage<String, EnforcedGuardrailConfiguration> raw = new WalStorage<>(path.resolve(SNAPSHOT),
                path.resolve(LOG), new TypeReference<>() {}, 600000);
        raw.load();
        return raw;
    }

    private BedrockService service(WalStorage<String, EnforcedGuardrailConfiguration> raw, String account) {
        return new BedrockService(guardrails, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(raw, null, account), new RegionResolver(REGION, account),
                mapper, mock(KmsService.class));
    }

    private String versionedGuardrail(BedrockService service) {
        return BedrockEnforcedGuardrailFixtures.publish(service, REGION, requestForGuardrail("east")).getGuardrailId();
    }

    private ObjectNode requestForGuardrail(String name) {
        return BedrockEnforcedGuardrailFixtures.guardrailRequest(mapper, name, "blocked", "blocked");
    }

    private ObjectNode request(String identifier, String version) {
        return BedrockEnforcedGuardrailFixtures.selectiveRequest(mapper, identifier, version);
    }

    private static List<EnforcedGuardrailConfiguration> configurations(BedrockService service, String region) {
        return service.listEnforcedGuardrailConfigurations(null, region).items();
    }
}
