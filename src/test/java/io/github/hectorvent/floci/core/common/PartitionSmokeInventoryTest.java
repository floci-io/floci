package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every service package that mints ARNs or AWS hosts is either exercised in each non-commercial
 * partition by {@link PartitionCrossServiceSmokeIntegrationTest} or listed, with a reason, in
 * {@code partition/smoke-exemptions.tsv}. A new service that mints through the shared helpers
 * therefore has to make that choice visibly: the literal gate cannot see a wrong region source,
 * only a test signed in another partition can.
 */
class PartitionSmokeInventoryTest {

    private static final Path SERVICES = Path.of("src/main/java/io/github/hectorvent/floci/services");
    private static final Path EXEMPTIONS = Path.of("src/test/resources/partition/smoke-exemptions.tsv");
    private static final Pattern MINTS = Pattern.compile(
            "buildArn\\(|buildGlobalArn\\(|\\bArn\\.of\\(|\\bArn\\.global\\(|\\bnew (AwsArnUtils\\.)?Arn\\("
                    + "|dnsSuffixFor\\(|AwsEndpoints\\.\\w+\\(");

    @Test
    void everyMintingPackageIsCoveredOrExempted() {
        Set<String> missing = new TreeSet<>(mintingPackages());
        missing.removeAll(PartitionCrossServiceSmokeIntegrationTest.COVERED_PACKAGES);
        missing.removeAll(readExemptions().keySet());

        assertTrue(missing.isEmpty(), "Service packages that mint ARNs or hosts with no partition smoke case: "
                + missing + ". Add a case to PartitionCrossServiceSmokeIntegrationTest and its COVERED_PACKAGES, "
                + "or a row with a reason to " + EXEMPTIONS + ".");
    }

    @Test
    void exemptionsAreNotStale() {
        Set<String> minting = mintingPackages();
        Set<String> stale = new TreeSet<>();
        for (String exempted : readExemptions().keySet()) {
            if (!minting.contains(exempted)
                    || PartitionCrossServiceSmokeIntegrationTest.COVERED_PACKAGES.contains(exempted)) {
                stale.add(exempted);
            }
        }

        assertTrue(stale.isEmpty(), "Rows in " + EXEMPTIONS + " whose package no longer mints ARNs or hosts, "
                + "or is now covered by the smoke test: " + stale + ". Remove them.");
    }

    @Test
    void exemptionFileIsSortedAndFreeOfDuplicates() {
        List<String> packages = exemptionLines().stream().map(line -> line.split("\t", 2)[0]).toList();

        assertEquals(packages.stream().distinct().toList(), packages, "Duplicate rows in " + EXEMPTIONS);
        assertEquals(packages.stream().sorted().toList(), packages, "Keep " + EXEMPTIONS + " sorted by package.");
    }

    @Test
    void coveredPackagesExist() {
        Set<String> unknown = new TreeSet<>(PartitionCrossServiceSmokeIntegrationTest.COVERED_PACKAGES);
        unknown.removeAll(mintingPackages());

        assertTrue(unknown.isEmpty(), "COVERED_PACKAGES names packages that do not mint ARNs or hosts: " + unknown);
    }

    private static Set<String> mintingPackages() {
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(SERVICES)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (mints(dir)) {
                    packages.add(dir.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(packages.size() > 50, "the source scan found almost no minting packages; is "
                + SERVICES + " the right path?");
        return packages;
    }

    private static boolean mints(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (MINTS.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> readExemptions() {
        Map<String, String> exemptions = new TreeMap<>();
        for (String line : exemptionLines()) {
            String[] columns = line.split("\t", 2);
            assertEquals(2, columns.length, "expected package<TAB>reason in " + EXEMPTIONS + ": " + line);
            assertTrue(!columns[1].isBlank(), "an exemption needs a reason: " + line);
            exemptions.put(columns[0], columns[1]);
        }
        return exemptions;
    }

    private static List<String> exemptionLines() {
        try {
            return Files.readAllLines(EXEMPTIONS, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
