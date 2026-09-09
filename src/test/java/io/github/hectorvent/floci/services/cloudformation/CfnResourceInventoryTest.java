package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the full set of CloudFormation resource types Floci provisions, and who serves each one.
 *
 * <p>This is the guard for the per-service provisioner migration. {@code provision}'s default arm
 * stubs an unknown type with {@code arn:aws:stub:::<logicalId>} and reports CREATE_COMPLETE, so a
 * type that falls out of both the switch and the registry would otherwise pass every test while
 * provisioning nothing. Making the inventory a checked-in file turns each migration slice into an
 * intentional two-line diff.
 *
 * <p>Runs under {@code @QuarkusTest} deliberately: the registry is compared as CDI resolved it, so
 * a provisioner written without {@code @ApplicationScoped} is caught. That mistake leaves the
 * source looking correct and is invisible to a plain unit test.
 */
@QuarkusTest
class CfnResourceInventoryTest {

    private static final Path INVENTORY =
            Path.of("src/test/resources/cloudformation/supported-resource-types.tsv");
    private static final Path SCHEMA_DIR = Path.of("local/aws/cfn-resource-schemas/us-east-1");
    private static final String LEGACY_OWNER = "LEGACY_SWITCH";

    /**
     * Types with no CloudFormation registry schema. {@code AWS::CDK::Metadata} is emitted by the
     * CDK toolchain rather than published by AWS, so it has no schema to check against.
     */
    private static final Set<String> TYPES_WITHOUT_SCHEMA = Set.of("AWS::CDK::Metadata");

    @Inject
    CloudFormationResourceRegistry registry;

    @Test
    void inventoryMatchesTheRegistryAndTheLegacySwitch() {
        Map<String, String> actual = new TreeMap<>();
        for (String type : registry.registeredTypes()) {
            actual.put(type, registry.ownerOf(type));
        }
        for (String type : CloudFormationResourceProvisioner.LEGACY_SWITCH_TYPES) {
            actual.put(type, LEGACY_OWNER);
        }

        assertEquals(render(readInventory()), render(actual),
                "The provisioned resource-type inventory changed. If that was intentional, write "
                        + "this exact content to " + INVENTORY + ".");
    }

    @Test
    void noTypeIsServedByBothTheRegistryAndTheSwitch() {
        List<String> both = registry.registeredTypes().stream()
                .filter(CloudFormationResourceProvisioner.LEGACY_SWITCH_TYPES::contains)
                .sorted()
                .toList();

        assertTrue(both.isEmpty(),
                "A type served by an extracted provisioner must have its switch arm removed; the "
                        + "registry wins, leaving the arm as dead code that still looks live: " + both);
    }

    @Test
    void inventoryFileIsSortedAndFreeOfDuplicates() {
        List<String> types = readTypeColumn();

        assertEquals(types.stream().distinct().toList(), types,
                "Duplicate type rows in " + INVENTORY + "; each type is served by exactly one owner.");
        assertEquals(types.stream().sorted().toList(), types,
                "Keep " + INVENTORY + " sorted by type so slice diffs stay reviewable.");
    }

    /**
     * Every declared AWS type exists in the published CloudFormation registry schemas. Catches a
     * typo'd type string, which is otherwise permanently silent: it never matches a template and
     * the default arm stubs it. Skipped where the schema corpus is absent (it lives under the
     * gitignored {@code local/}), so this is a local correctness aid, not a CI gate.
     */
    @Test
    void everyDeclaredTypeIsARealAwsResourceType() {
        if (!Files.isDirectory(SCHEMA_DIR)) {
            return;
        }
        List<String> unknown = readInventory().keySet().stream()
                .filter(type -> type.startsWith("AWS::"))
                .filter(type -> !TYPES_WITHOUT_SCHEMA.contains(type))
                .filter(type -> !Files.exists(SCHEMA_DIR.resolve(schemaFileName(type))))
                .toList();

        assertTrue(unknown.isEmpty(), "No CloudFormation registry schema for: " + unknown);
    }

    /** {@code AWS::SQS::Queue} to {@code aws-sqs-queue.json}. */
    private static String schemaFileName(String type) {
        return type.replace("::", "-").toLowerCase(Locale.ROOT) + ".json";
    }

    /** The type column in file order, so duplicate and ordering defects survive to be asserted. */
    private static List<String> readTypeColumn() {
        try {
            return Files.readAllLines(INVENTORY, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split("\t", 2)[0])
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + INVENTORY, e);
        }
    }

    private static Map<String, String> readInventory() {
        try {
            Map<String, String> rows = new LinkedHashMap<>();
            for (String line : Files.readAllLines(INVENTORY, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                assertEquals(2, parts.length, "Expected 'type<TAB>owner' but found: " + line);
                rows.put(parts[0], parts[1]);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + INVENTORY, e);
        }
    }

    private static String render(Map<String, String> rows) {
        return rows.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "\t" + e.getValue())
                .collect(Collectors.joining("\n"));
    }
}
