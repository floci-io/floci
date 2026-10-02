package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerStorageHelperTest {

    @Test
    void resourceNamesCarryThisEmulatorsPrefix() {
        assertEquals("floci-aws-rds-db1", ContainerStorageHelper.resourceName("rds", null, "db1"));
        assertEquals("floci-aws-rds-vol1", ContainerStorageHelper.resourceName(config(""), "rds", "vol1", "db1"));
        assertEquals("floci-aws-opensearch-domain1",
                ContainerStorageHelper.resourceName(config(""), "opensearch", null, "domain1"));
        assertEquals("floci-aws-ec2-i-123", ContainerStorageHelper.dockerName(config(""), "ec2-i-123"));
    }

    @Test
    void alreadyPrefixedNamesAreNormalisedNotPrefixedTwice() {
        // A persisted name is fed straight back in when a container is named after the volume it
        // mounts, so prefixing has to be idempotent in both directions.
        assertEquals("floci-aws-ec2-i-123", ContainerStorageHelper.dockerName(config(""), "floci-ec2-i-123"));
        assertEquals("floci-aws-ec2-i-123", ContainerStorageHelper.dockerName(config(""), "floci-aws-ec2-i-123"));
        assertEquals("floci-aws-ui", ContainerStorageHelper.dockerName(config(""), "floci-ui"));
    }

    @Test
    void legacyNamesKeepTheFrozenPreMigrationShape() {
        // Frozen forever: this is the name a pre-migration volume's data actually lives under.
        assertEquals("floci-rds-vol1",
                ContainerStorageHelper.legacyResourceName(config(""), "rds", "vol1", "db1"));
        assertEquals("floci-rds-db1",
                ContainerStorageHelper.legacyResourceName(config(""), "rds", null, "db1"));
        assertEquals("floci-run-one-rds-vol1",
                ContainerStorageHelper.legacyResourceName(config(" run/one "), "rds", "vol1", "db1"));
        // Round-trips back from a current name, which is how a container derives its legacy twin.
        assertEquals("floci-rds-vol1",
                ContainerStorageHelper.legacyDockerName(config(""), "floci-aws-rds-vol1"));
    }

    @Test
    void resourceNamesIncludeSanitizedNamespaceWhenConfigured() {
        EmulatorConfig config = config(" run/one ");

        // The namespace lands after the cloud token, never before it.
        assertEquals("floci-aws-run-one-rds-db1", ContainerStorageHelper.resourceName(config, "rds", null, "db1"));
        assertEquals("floci-aws-run-one-rds-vol1", ContainerStorageHelper.resourceName(config, "rds", "vol1", "db1"));
        assertEquals("floci-aws-run-one-ec2-i-123", ContainerStorageHelper.dockerName(config, "floci-ec2-i-123"));
        assertEquals("floci-aws-run-one-ui", ContainerStorageHelper.dockerName(config, "floci-ui"));
        // Re-normalising either shape is a no-op.
        assertEquals("floci-aws-run-one-ec2-i-123",
                ContainerStorageHelper.dockerName(config, "floci-aws-run-one-ec2-i-123"));
    }

    @Test
    void hostResourcePathsIncludeNamespaceWhenConfigured() {
        EmulatorConfig config = config("run-one");

        assertEquals(Path.of("/tmp/floci/run-one/rds/db1"), ContainerStorageHelper.hostResourcePath(config, "rds", "db1"));
    }

    @Test
    void unsafeNamespaceSegmentsAreIgnored() {
        EmulatorConfig config = config("..");

        assertEquals(Path.of("/tmp/floci/rds/db1"), ContainerStorageHelper.hostResourcePath(config, "rds", "db1"));
        assertEquals("floci-aws-rds-db1", ContainerStorageHelper.resourceName(config, "rds", null, "db1"));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void defaultLabelsIdentifyThisEmulator() {
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws"),
                ContainerStorageHelper.defaultLabels(config("")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws", "floci_namespace", "run-one"),
                ContainerStorageHelper.defaultLabels(config(" run/one ")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws"),
                ContainerStorageHelper.defaultLabels(null));
    }

    @Test
    void prefixedDockerNameSwapsTheBasePrefix() {
        assertEquals("acme-my-fn-abc123",
                ContainerStorageHelper.prefixedDockerName(config(""), "acme", "my-fn-abc123"));
        assertEquals("acme-run-one-my-fn-abc123",
                ContainerStorageHelper.prefixedDockerName(config(" run/one "), "acme", "my-fn-abc123"));
        // This emulator's own prefix through this path matches what dockerName produces.
        assertEquals(ContainerStorageHelper.dockerName(config("run-one"), "my-fn-abc123"),
                ContainerStorageHelper.prefixedDockerName(
                        config("run-one"), ContainerStorageHelper.NAME_PREFIX, "my-fn-abc123"));
    }

    @Test
    void extraLabelsAreMergedIntoDefaultLabels() {
        // A dotted key — exactly the shape that motivates list-of-entries config over a Map,
        // whose env-var naming convention cannot express such keys.
        EmulatorConfig config = config("", List.of(
                label("com.example.project", "my-project"),
                label("environment", "dev")));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws",
                        "com.example.project", "my-project", "environment", "dev"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void extraLabelsCannotOverrideReservedKeys() {
        // The reserved labels drive container/volume discovery and pruning; a user label must
        // never be able to break `docker volume prune --filter label=floci=true` cleanup.
        EmulatorConfig config = config(" run/one ", List.of(
                label("floci", "false"),
                label("floci_emulator", "spoofed"),
                label("floci_namespace", "spoofed"),
                label("kept", "yes")));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws",
                        "floci_namespace", "run-one", "kept", "yes"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void blankExtraLabelKeysAreIgnored() {
        EmulatorConfig config = config("", List.of(
                label("  ", "dropped"),
                label(null, "dropped"),
                label("kept", null)));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws", "kept", ""),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void resourceIdentityLabelsCarryTheFullEmulatedResourceIdentity() {
        assertEquals(
                Map.of("io.floci", "aws",
                        "io.floci.service", "rds",
                        "io.floci.resource-id", "orders-db-primary",
                        "io.floci.account", "000000000000",
                        "io.floci.region", "us-east-1"),
                ContainerStorageHelper.resourceIdentityLabels(
                        "rds", "orders-db-primary", "000000000000", "us-east-1"));
    }

    @Test
    void resourceIdentityLabelsOmitBlankOrMissingValues() {
        // ECR's sibling registry is a shared singleton with no per-resource identifier.
        assertEquals(
                Map.of("io.floci", "aws", "io.floci.service", "ecr"),
                ContainerStorageHelper.resourceIdentityLabels("ecr", null, "", "   "));
    }

    @Test
    void extraLabelsCannotSetAnAliasedKeyNewOrLegacy() {
        // A cleanup path trusts the owner and identity labels; a user label must never fake one.
        EmulatorConfig config = config("", List.of(
                label("io.floci.owner", "spoofed"),
                label("floci_owner_port", "spoofed"),
                label("io.floci.ecs.run", "spoofed"),
                label("floci.ecs-run", "spoofed"),
                label("floci.security-group-helper", "true"),
                label("io.floci.component", "spoofed"),
                label("floci_vpc_owner_port", "spoofed"),
                label("floci_vpc_id", "spoofed"),
                label("io.floci.resource-id", "spoofed"),
                label("floci_component", "ec2-vpc"),
                label("kept", "yes")));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws", "kept", "yes"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void ownerIdentityPrefixesTheResourceNamespaceToThePort() {
        EmulatorConfig plain = config("");
        when(plain.port()).thenReturn(4566);
        EmulatorConfig namespaced = config("alpha");
        when(namespaced.port()).thenReturn(4566);

        assertEquals("4566", ContainerStorageHelper.ownerIdentity(plain));
        assertEquals("alpha/4566", ContainerStorageHelper.ownerIdentity(namespaced));
    }

    @Test
    void containerAliasesWriteEachLegacyKeyWithTheNewKeysValue() {
        Map<String, String> labels = ContainerStorageHelper.CONTAINER_LABEL_ALIASES.withLegacyAliases(Map.of(
                "io.floci.owner", "alpha/4566",
                "io.floci.ecs.run", "run-1",
                "io.floci.ecs.credentials-proxy", "true",
                "io.floci.security-group.helper", "true",
                "io.floci.security-group.workload", "true",
                "io.floci.component", "pipes-kafka-rest-bridge",
                "io.floci.service", "ecs"));

        assertEquals("alpha/4566", labels.get("floci_owner_port"));
        assertEquals("run-1", labels.get("floci.ecs-run"));
        assertEquals("true", labels.get("floci.ecs-task-role-credentials-proxy"));
        assertEquals("true", labels.get("floci.security-group-helper"));
        assertEquals("true", labels.get("floci.security-group-workload"));
        assertEquals("pipes-kafka-rest-bridge", labels.get("floci.component"));
        assertEquals("alpha/4566", labels.get("io.floci.owner"), "the new key stays");
        assertEquals("ecs", labels.get("io.floci.service"));
        assertEquals(13, labels.size(), "a key without an alias gains nothing");
    }

    @Test
    void networkAliasesUseTheirOwnLegacyKeysAndTheComposite() {
        Map<String, String> labels = ContainerStorageHelper.NETWORK_LABEL_ALIASES.withLegacyAliases(Map.of(
                "io.floci.owner", "alpha/4566",
                "io.floci.resource-id", "vpc-1",
                "io.floci.region", "us-east-1",
                "io.floci.service", "ec2",
                "io.floci.component", "vpc-network"));

        assertEquals("alpha/4566", labels.get("floci_vpc_owner_port"));
        assertFalse(labels.containsKey("floci_owner_port"), "the container alias of the owner is not a network's");
        assertEquals("vpc-1", labels.get("floci_vpc_id"));
        assertEquals("us-east-1", labels.get("floci_vpc_region"));
        assertEquals("ec2-vpc", labels.get("floci_component"));
    }

    @Test
    void theCompositeAliasNeedsEveryOneOfItsNewLabels() {
        Map<String, String> labels = ContainerStorageHelper.NETWORK_LABEL_ALIASES.withLegacyAliases(Map.of(
                "io.floci.service", "ec2", "io.floci.component", "something-else"));

        assertFalse(labels.containsKey("floci_component"));
    }

    @Test
    void labelValuePrefersTheNewKeyAndFallsBackToTheLegacyOne() {
        ContainerStorageHelper.LabelAliases aliases = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;

        assertEquals("4566", aliases.labelValue(Map.of("io.floci.owner", "4566"), "io.floci.owner"));
        assertEquals("4566", aliases.labelValue(Map.of("floci_owner_port", "4566"), "io.floci.owner"));
        assertEquals("4566", aliases.labelValue(
                Map.of("io.floci.owner", "4566", "floci_owner_port", "4566"), "io.floci.owner"));
        assertEquals("4566", aliases.labelValue(
                Map.of("io.floci.owner", "4566", "floci_owner_port", "4567"), "io.floci.owner"));
        assertNull(aliases.labelValue(Map.of(), "io.floci.owner"));
        assertNull(aliases.labelValue(null, "io.floci.owner"));
        assertEquals("run-1", ContainerStorageHelper.labelValue(Map.of("floci.ecs-run", "run-1"), "io.floci.ecs.run"));
    }

    @Test
    void labelValueReadsTheCompositeBackIntoEachOfItsNewKeys() {
        ContainerStorageHelper.LabelAliases aliases = ContainerStorageHelper.NETWORK_LABEL_ALIASES;
        Map<String, String> legacy = Map.of("floci_component", "ec2-vpc", "floci_vpc_id", "vpc-1");

        assertEquals("ec2", aliases.labelValue(legacy, "io.floci.service"));
        assertEquals("vpc-network", aliases.labelValue(legacy, "io.floci.component"));
        assertEquals("vpc-1", aliases.labelValue(legacy, "io.floci.resource-id"));
    }

    @Test
    void matchesRequiresEveryOwnerKeyPresentToAgree() {
        ContainerStorageHelper.LabelAliases aliases = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;

        assertTrue(aliases.matches(Map.of("io.floci.owner", "4566"), "io.floci.owner", "4566"));
        assertTrue(aliases.matches(Map.of("floci_owner_port", "4566"), "io.floci.owner", "4566"));
        assertTrue(aliases.matches(
                Map.of("io.floci.owner", "4566", "floci_owner_port", "4566"), "io.floci.owner", "4566"));
        assertFalse(aliases.matches(
                Map.of("io.floci.owner", "4566", "floci_owner_port", "4567"), "io.floci.owner", "4566"));
        assertFalse(aliases.matches(
                Map.of("io.floci.owner", "4567", "floci_owner_port", "4566"), "io.floci.owner", "4566"));
        assertFalse(aliases.matches(Map.of(), "io.floci.owner", "4566"), "an unowned object matches nobody");
    }

    @Test
    void consistentRejectsAnObjectWhoseNewAndLegacyLabelsDisagree() {
        ContainerStorageHelper.LabelAliases containers = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;
        ContainerStorageHelper.LabelAliases networks = ContainerStorageHelper.NETWORK_LABEL_ALIASES;

        assertTrue(containers.consistent("c", Map.of("io.floci.owner", "4566")));
        assertTrue(containers.consistent("c", Map.of("floci_owner_port", "4566")));
        assertTrue(containers.consistent("c", Map.of("io.floci.owner", "4566", "floci_owner_port", "4566")));
        assertFalse(containers.consistent("c", Map.of("io.floci.owner", "4566", "floci_owner_port", "4567")));
        assertFalse(containers.consistent("c", Map.of("io.floci.ecs.run", "a", "floci.ecs-run", "b")));
        assertTrue(networks.consistent("n", Map.of(
                "io.floci.service", "ec2", "io.floci.component", "vpc-network", "floci_component", "ec2-vpc")));
        assertFalse(networks.consistent("n", Map.of("io.floci.component", "other", "floci_component", "ec2-vpc")));
        assertFalse(networks.consistent("n", Map.of("io.floci.resource-id", "vpc-1", "floci_vpc_id", "vpc-2")));
    }

    @Test
    void labelFiltersSpellTheSameFilterWithTheLegacyKeys() {
        assertEquals(
                List.of(Map.of("io.floci.service", "ecs", "io.floci.owner", "4566"),
                        Map.of("io.floci.service", "ecs", "floci_owner_port", "4566")),
                ContainerStorageHelper.CONTAINER_LABEL_ALIASES.labelFilters(
                        Map.of("io.floci.service", "ecs", "io.floci.owner", "4566")));
        assertEquals(
                List.of(Map.of("io.floci.service", "ec2", "io.floci.component", "vpc-network"),
                        Map.of("floci_component", "ec2-vpc")),
                ContainerStorageHelper.NETWORK_LABEL_ALIASES.labelFilters(
                        Map.of("io.floci.service", "ec2", "io.floci.component", "vpc-network")));
        assertEquals(
                List.of(Map.of("io.floci.service", "ec2")),
                ContainerStorageHelper.CONTAINER_LABEL_ALIASES.labelFilters(Map.of("io.floci.service", "ec2")),
                "a filter with no aliased key is queried once");
    }

    @Test
    void listByLabelsQueriesEachKeySetAndListsEveryObjectOnce() {
        Map<Map<String, String>, List<String>> daemon = Map.of(
                Map.of("io.floci.owner", "4566"), List.of("new-only", "both"),
                Map.of("floci_owner_port", "4566"), List.of("legacy-only", "both"));

        List<String> found = ContainerStorageHelper.CONTAINER_LABEL_ALIASES.listByLabels(
                Map.of("io.floci.owner", "4566"), daemon::get, id -> id);

        assertEquals(List.of("new-only", "both", "legacy-only"), found);
    }

    private static EmulatorConfig.DockerConfig.LabelEntry label(String key, String value) {
        return new EmulatorConfig.DockerConfig.LabelEntry() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }

    private static EmulatorConfig config(String namespace) {
        return config(namespace, List.of());
    }

    private static EmulatorConfig config(
            String namespace, java.util.List<EmulatorConfig.DockerConfig.LabelEntry> extraLabels) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(docker.resourceNamespace()).thenReturn(namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        when(docker.extraLabels()).thenReturn(extraLabels);
        when(storage.hostPersistentPath()).thenReturn("/tmp/floci");
        return config;
    }
}
