package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.model.EventBus;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.services.eventbridge.model.Target;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventBridgeTargetLookupPersistenceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String TARGET = "arn:aws:sqs:us-east-1:" + ACCOUNT + ":owned";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "hybrid,false", "hybrid,true", "wal,false", "wal,true"})
    void loadedScopedAndLegacyRecordsRemainUnchangedAcrossReadsAndReload(String mode, boolean legacy) throws Exception {
        String prefix = legacy ? "" : ACCOUNT + "/";
        String busKey = prefix + "bus:" + REGION + ":custom";
        String ruleKey = prefix + "rule:" + REGION + ":custom/owned";
        EventBus bus = new EventBus("custom", "arn:aws:events:" + REGION + ":" + ACCOUNT + ":event-bus/custom", null, null);
        Rule rule = new Rule();
        rule.setName("owned");
        rule.setEventBusName("custom");
        rule.setArn("arn:aws:events:" + REGION + ":" + ACCOUNT + ":rule/custom/owned");
        rule.setAccountId(legacy ? null : ACCOUNT);
        String suffix = "wal".equals(mode) ? "-snapshot.json" : ".json";
        Files.writeString(directory.resolve("eventbridge-buses" + suffix), MAPPER.writeValueAsString(Map.of(busKey, bus)));
        Files.writeString(directory.resolve("eventbridge-rules" + suffix), MAPPER.writeValueAsString(Map.of(ruleKey, rule)));
        Files.writeString(directory.resolve("eventbridge-targets" + suffix), MAPPER.writeValueAsString(
                Map.of(ruleKey, List.of(new Target("one", TARGET, null, null)))));

        for (int round = 0; round < 2; round++) {
            StorageFactory factory = factory(mode);
            try {
                AccountAwareStorageBackend<EventBus> buses = factory.create("eventbridge", "eventbridge-buses.json",
                        new TypeReference<Map<String, EventBus>>() {});
                AccountAwareStorageBackend<Rule> rules = factory.create("eventbridge", "eventbridge-rules.json",
                        new TypeReference<Map<String, Rule>>() {});
                AccountAwareStorageBackend<List<Target>> targets = factory.create("eventbridge", "eventbridge-targets.json",
                        new TypeReference<Map<String, List<Target>>>() {});
                String before = MAPPER.writeValueAsString(List.of(buses.scanAllAccountsWithRawKeys(),
                        rules.scanAllAccountsWithRawKeys(), targets.scanAllAccountsWithRawKeys()));
                Map<String, String> files = fileDigests();
                EventBridgeTargetLookup lookup = new EventBridgeTargetLookup(buses, rules, targets,
                        new RegionResolver(REGION, ACCOUNT));
                assertEquals(List.of("owned"), lookup.list(TARGET, "custom", 100, null, REGION).items());
                assertEquals(List.of("owned"), lookup.list(TARGET, bus.getArn(), 100, null, REGION).items());
                assertEquals(List.of(), lookup.list(TARGET, null, 100, null, REGION).items());
                assertEquals(before, MAPPER.writeValueAsString(List.of(buses.scanAllAccountsWithRawKeys(),
                        rules.scanAllAccountsWithRawKeys(), targets.scanAllAccountsWithRawKeys())));
                assertEquals(Set.of(busKey), buses.scanAllAccountsWithRawKeys().keySet());
                assertEquals(Set.of(ruleKey), rules.scanAllAccountsWithRawKeys().keySet());
                assertEquals(Set.of(ruleKey), targets.scanAllAccountsWithRawKeys().keySet());
                assertEquals(files, fileDigests(), "the read must neither write storage bytes nor add files");
            } finally {
                factory.shutdownAll();
            }
        }
    }

    private Map<String, String> fileDigests() throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(directory.relativize(file).toString(), Digests.sha256Hex(Files.readAllBytes(file)));
            }
        }
        return hashes;
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(config, access);
    }
}
