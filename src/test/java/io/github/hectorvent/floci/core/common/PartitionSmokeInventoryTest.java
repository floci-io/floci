package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.MainSources;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every service package that mints ARNs or AWS hosts is either exercised in each non-commercial
 * partition by {@link PartitionCrossServiceSmokeIntegrationTest} or listed, with a reason, in
 * {@code partition/smoke-exemptions.tsv}. A new service that mints through the shared helpers
 * therefore has to make that choice visibly: the literal gate cannot see a wrong region source,
 * only a test signed in another partition can.
 */
class PartitionSmokeInventoryTest {

    private static final Path SERVICES = MainSources.BASE_DIR.resolve("services");
    private static final Path EXEMPTIONS = Path.of("src/test/resources/partition/smoke-exemptions.tsv");
    private static final Pattern MINTS = Pattern.compile(
            "buildArn\\(|buildGlobalArn\\(|\\bArn\\.of\\(|\\bArn\\.global\\(|\\bnew (AwsArnUtils\\.)?Arn\\("
                    + "|dnsSuffixFor\\(|AwsEndpoints\\.\\w+\\("
                    // An ARN concatenated by hand mints as surely as Arn.of; a PARTITION_REGEX pattern only matches one.
                    + "|\"arn:\"\\s*\\+(?!\\s*(?:AwsArnUtils\\s*\\.\\s*)?PARTITION_REGEX\\b)");

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

    @Test
    void commentsNeitherMintNorHideAPattern() {
        assertFalse(mintsIn("Pattern p = Pattern.compile(\"arn:\" + /* partition */ AwsArnUtils.PARTITION_REGEX"
                + " + \":iam::\\\\d{12}:role/.+\");"));
        assertFalse(mintsIn("// String arn = \"arn:\" + partition;\n/** see Arn.of( */ int x;"));
        assertTrue(mintsIn("String arn = \"arn:\" + /* the request's */ partition + \":iam::\" + account;"));
        assertTrue(mintsIn("String url = \"https://\" + host; Arn.of(service, region, account, resource);"));
    }

    private static boolean mintsIn(String source) {
        return MINTS.matcher(codeText(source)).find();
    }

    /**
     * The source with every comment and text block blanked and string literals kept, as
     * {@code code_text} in {@code tools/partition/partition_literals.py} does: a {@code //} inside a
     * string is a URL, not a comment, and a call shape in a comment or javadoc never counts.
     */
    static String codeText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(" ".repeat(end - i));
                i = end;
            } else if (text.startsWith("/*", i) || text.startsWith("\"\"\"", i)) {
                String closer = text.charAt(i + 1) == '*' ? "*/" : "\"\"\"";
                int j = i + closer.length();
                while (j < n && !text.startsWith(closer, j)) {
                    j += closer.equals("\"\"\"") && text.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(j + closer.length(), n);
                for (int k = i; k < end; k++) {
                    out.append(text.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                char quote = text.charAt(i);
                int j = i + 1;
                while (j < n && text.charAt(j) != quote && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                int end = j < n && text.charAt(j) == quote ? j + 1 : Math.min(j, n);
                out.append(text, i, end);
                i = end;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
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
                if (mintsIn(Files.readString(file, StandardCharsets.UTF_8))) {
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
