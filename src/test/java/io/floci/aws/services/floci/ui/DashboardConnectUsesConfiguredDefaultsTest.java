package io.floci.aws.services.floci.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dashboard's Connect panel, which shows the region and account an SDK should use and
 * the Shell and boto3 snippets built from them: they come from the defaults {@code /_floci/info}
 * reports, as soon as it answers, and the built-in values stay when it does not report them.
 */
class DashboardConnectUsesConfiguredDefaultsTest {

    private static final Path LANDING_PAGE = Path.of("src", "main", "resources", "ui", "index.html");
    private static final Pattern REGION_LABEL_READ = Pattern.compile("\\(\"f-region\"\\)\\.textContent\\s*(?:[^=\\s]|==)");
    private static final Pattern INFO_RENDERED_ON_ARRIVAL =
            Pattern.compile("getJson\\(\"/_floci/info\"\\)\\.then\\(function \\(manifest\\) \\{\\s*renderConnect\\(manifest\\);");

    // Catches: a snippet that hardcodes us-east-1, so an emulator on another region hands out
    // commands and code that point the SDK at the wrong one.
    @Test
    void snippetsUseTheRegionTheManifestReports() {
        String content = readLandingPage();

        assertTrue(content.contains("AWS_DEFAULT_REGION=\" + connectRegion"),
                "the Shell snippet must take its region from connectRegion");
        assertTrue(content.contains("region_name=\"' + connectRegion"),
                "the boto3 snippet must take its region from connectRegion");
        assertFalse(content.contains("AWS_DEFAULT_REGION=us-east-1") || content.contains("region_name=\"us-east-1\""),
                "no snippet may hardcode the built-in region");
    }

    // Catches: a snippet that reads the region back from the panel's label, so any text added to
    // the label lands in the generated commands and code.
    @Test
    void snippetsNeverReadTheRegionBackFromThePanelLabel() {
        assertFalse(REGION_LABEL_READ.matcher(readLandingPage()).find(),
                "index.html must only write the region label, never read it back");
    }

    // Catches: the Connect panel waiting for every dashboard request to settle, so a slow
    // /_floci/ui/status holds back the region and account the manifest already gave.
    @Test
    void theManifestIsAppliedAsSoonAsInfoAnswers() {
        assertTrue(INFO_RENDERED_ON_ARRIVAL.matcher(readLandingPage()).find(),
                "index.html must render the Connect defaults in /_floci/info's own callback");
    }

    // Catches: a manifest without defaults, or the region or account missing from them, blanking
    // the built-in values shown before it arrived.
    @Test
    void theBuiltInValuesStayWhenTheManifestOmitsThem() {
        String content = readLandingPage();

        assertTrue(content.contains("<dd id=\"f-region\">us-east-1</dd>")
                        && content.contains("<dd id=\"f-account\">000000000000</dd>")
                        && content.contains("var connectRegion = \"us-east-1\";"),
                "the panel and the snippets must start from the built-in region and account");
        assertTrue(content.contains("if (!defaults) { return; }")
                        && content.contains("if (defaults.account_id) {")
                        && content.contains("if (defaults.region && defaults.region !== connectRegion) {"),
                "the panel must change a value only when the manifest reports it");
    }

    private static String readLandingPage() {
        try {
            return Files.readString(LANDING_PAGE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
