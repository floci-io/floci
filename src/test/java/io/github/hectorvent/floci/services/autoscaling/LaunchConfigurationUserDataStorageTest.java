package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LaunchConfigurationUserDataStorageTest {
    private static final String REGION = "us-east-1";
    @TempDir Path directory;
    private final List<StorageFactory> factories = new ArrayList<>();

    static Stream<Arguments> representations() {
        return Stream.of("persistent", "hybrid", "wal").flatMap(mode -> Stream.of(
                Arguments.of(mode, "new", "YQ", "a", "YQ"),
                Arguments.of(mode, "new", "/w", "\ufffd", "/w"),
                Arguments.of(mode, "new", "", null, ""),
                Arguments.of(mode, "new", null, null, null),
                Arguments.of(mode, "source", "/w", "\ufffd", "/w"),
                Arguments.of(mode, "source-legacy", "YQ==", "YQ==", "WVE9PQ=="),
                Arguments.of(mode, "legacy", "YQ==", "YQ==", "WVE9PQ=="),
                Arguments.of(mode, "legacy", "literal text", "literal text", "bGl0ZXJhbCB0ZXh0")));
    }

    @ParameterizedTest
    @MethodSource("representations")
    void reloadRetainsKnownEncodingWithoutGuessingLegacyText(String mode, String origin,
                                                            String input, String text, String encoded)
            throws IOException {
        AutoScalingService service = storedService(mode);
        LaunchConfiguration created;
        Ec2Service ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
        if (origin.startsWith("source")) {
            Instance source = new Instance();
            source.setImageId("ami-original");
            source.setInstanceType("t3.micro");
            source.setUserData(origin.equals("source-legacy") ? input : "\ufffd");
            source.setEncodedUserData(origin.equals("source-legacy") ? null : input);
            Reservation sourceReservation = new Reservation();
            sourceReservation.setInstances(List.of(source));
            when(ec2.describeInstances(REGION, List.of("i-source"), Map.of()))
                    .thenReturn(List.of(sourceReservation));
            created = service.createLaunchConfiguration(REGION, "config", "i-source", null,
                    null, null, List.of(), null, null, null);
        } else if (origin.equals("legacy")) {
            created = new ObjectMapper().readValue("""
                    {"launchConfigurationName":"config","imageId":"ami-original",
                     "instanceType":"t3.micro","region":"us-east-1"}
                    """, LaunchConfiguration.class);
            assertNull(created.getUserDataBase64Encoded());
            created.setUserData(input);
            AccountAwareStorageBackend<LaunchConfiguration> store = service.storageFactory.create("autoscaling",
                    "autoscaling-launch-configurations.json", new TypeReference<Map<String, LaunchConfiguration>>() {});
            store.put(REGION + "::config", created);
        } else {
            created = service.createLaunchConfiguration(REGION, "config", null, "ami-original",
                    "t3.micro", null, List.of(), input, null, null);
        }
        factories.removeFirst().shutdownAll();
        String snapshot = mode.equals("wal") ? "autoscaling-launch-configurations-snapshot.json"
                : "autoscaling-launch-configurations.json";
        assertTrue(Files.exists(directory.resolve(snapshot)), "No real launch-configuration snapshot was written");
        service = storedService(mode);
        service.ec2Service = ec2;
        LaunchConfiguration restored = service.describeLaunchConfigurations(REGION, List.of("config")).getFirst();
        assertNotSame(created, restored);
        assertEquals(origin.equals("legacy") ? null : Boolean.TRUE, restored.getUserDataBase64Encoded());
        assertEquals(origin.equals("legacy") ? input : encoded, restored.getUserData());
        service.createAutoScalingGroup(REGION, "group", "config", null, null, null, null,
                0, 1, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("group")).getFirst();
        Instance launched = new Instance();
        launched.setInstanceId("i-created");
        Reservation result = new Reservation();
        result.setInstances(List.of(launched));
        when(ec2.runInstances(eq(REGION), eq("ami-original"), eq("t3.micro"), eq(1), eq(1),
                isNull(), anyList(), isNull(), isNull(), anyList(), eq(text), isNull(), isNull(),
                isNull(), eq(0), isNull(), isNull(), isNull(), eq(encoded),
                anyBoolean(), isNull(), isNull(), any())).thenReturn(result);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class));
        try {
            reconciler.reconcile(group);
            assertEquals(List.of("i-created"), group.getInstances().stream().map(i -> i.getInstanceId()).toList());
            verify(ec2).runInstances(eq(REGION), eq("ami-original"), eq("t3.micro"), eq(1), eq(1),
                    isNull(), anyList(), isNull(), isNull(), anyList(), eq(text), isNull(), isNull(),
                    isNull(), eq(0), isNull(), isNull(), isNull(), eq(encoded),
                    anyBoolean(), isNull(), isNull(), any());
            assertEquals(origin.equals("legacy") ? null : Boolean.TRUE, restored.getUserDataBase64Encoded());
            assertEquals(origin.equals("legacy") ? input : encoded, restored.getUserData());
        } finally {
            reconciler.stop();
        }
    }

    private AutoScalingService storedService(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.WalConfig wal = mock(EmulatorConfig.WalConfig.class);
        when(config.storage()).thenReturn(storage);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(storage.persistentPath()).thenReturn(directory.toString());
        when(storage.wal()).thenReturn(wal);
        when(wal.compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("autoscaling")).thenReturn(mode);
        when(access.storageFlushInterval("autoscaling")).thenReturn(60000L);
        StorageFactory factory = new StorageFactory(config, access);
        factories.add(factory);
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.storageFactory = factory;
        service.initializeStorage();
        return service;
    }

    @AfterEach
    void closeStorage() {
        factories.forEach(StorageFactory::shutdownAll);
    }
}
