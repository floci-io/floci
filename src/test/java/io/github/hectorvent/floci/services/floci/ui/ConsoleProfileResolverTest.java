package io.github.hectorvent.floci.services.floci.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleProfileResolverTest {

    private final EmulatorConfig.UiServiceConfig ui = mock(EmulatorConfig.UiServiceConfig.class);

    /** Nothing configured: the state an operator who only set an image is in. */
    private void nothingConfigured() {
        when(ui.internalPort()).thenReturn(OptionalInt.empty());
        when(ui.endpointEnv()).thenReturn(Optional.empty());
        when(ui.statusPath()).thenReturn(Optional.empty());
        when(ui.statusReadyField()).thenReturn(Optional.empty());
        when(ui.statusReadyValue()).thenReturn(Optional.empty());
        when(ui.statusUnavailableValue()).thenReturn(Optional.empty());
    }

    private static Map<String, String> contractLabels(String... pairs) {
        Map<String, String> labels = new HashMap<>();
        labels.put(ConsoleProfileResolver.LABEL_CONTRACT, ConsoleProfileResolver.CONTRACT_VERSION);
        for (int i = 0; i < pairs.length; i += 2) {
            labels.put(ConsoleProfileResolver.LABEL_PREFIX + pairs[i], pairs[i + 1]);
        }
        return labels;
    }

    // --- layer 4: the contract itself ---

    @Test
    void aConsoleThatImplementsTheContractNeedsNoConfigurationAtAll() {
        nothingConfigured();

        ConsoleProfile profile = ConsoleProfileResolver.resolve("acme/console:1", Optional.empty(), ui);

        assertEquals(4500, profile.internalPort());
        assertEquals("/api/health", profile.healthPath());
        assertEquals("status", profile.healthReadyField());
        assertEquals("ok", profile.healthReadyValue());
        assertEquals("unavailable", profile.healthUnavailableValue());
        assertFalse(profile.hasEndpointAlias(),
                "AWS_ENDPOINT_URL is in the baseline, so a contract console needs no alias");
    }

    // --- layer 3: a console Floci recognises by name ---

    @Test
    void flociUiKeepsItsOwnHealthShapeHoweverTheImageIsSpelled() {
        for (String image : new String[]{
                "floci-ui",
                "floci/floci-ui",
                "floci/floci-ui:latest",
                "docker.io/floci/floci-ui:2.0.1",
                "ghcr.io/floci-io/floci-ui@sha256:0123456789abcdef",
                "registry.example.com:5000/mirror/floci/floci-ui:latest"}) {
            ConsoleProfile profile = ConsoleProfileResolver.builtIn(image);

            assertEquals("/api/clouds/aws/status", profile.healthPath(), image);
            assertEquals("runtime", profile.healthReadyField(), image);
            assertEquals("reachable", profile.healthReadyValue(), image);
        }
    }

    @Test
    void anImageThatMerelyLooksLikeFlociUiGetsTheContractDefaults() {
        for (String image : new String[]{"floci/floci-uix:1", "acme/floci-ui-next", "floci/console"}) {
            assertEquals("/api/health", ConsoleProfileResolver.builtIn(image).healthPath(), image);
        }
    }

    // --- layer 2: the image describes itself ---

    @Test
    void labelsDescribeAConsoleFlociHasNeverHeardOf() {
        nothingConfigured();
        Map<String, String> labels = contractLabels(
                "port", "8080",
                "health-path", "healthz",
                "health-ready-field", "state",
                "health-ready-value", "up",
                "health-unavailable-value", "down",
                "endpoint-env", "CONSOLE_API_URL",
                "name", "StackPort");

        ConsoleProfile profile = ConsoleProfileResolver.resolve("acme/console:1", Optional.of(labels), ui);

        assertEquals(8080, profile.internalPort());
        assertEquals("/healthz", profile.healthPath(), "a label without a leading slash still forms a URL");
        assertEquals("state", profile.healthReadyField());
        assertEquals("up", profile.healthReadyValue());
        assertEquals("down", profile.healthUnavailableValue());
        assertEquals("CONSOLE_API_URL", profile.endpointEnv());
        assertEquals("StackPort", profile.displayName());
    }

    @Test
    void labelsForAContractVersionFlociDoesNotImplementAreIgnored() {
        // Reading a future contract's labels under this one's meaning is worse than ignoring them:
        // the console would be probed at a path that means something else.
        Map<String, String> labels = new HashMap<>(contractLabels("health-path", "/v2/health"));
        labels.put(ConsoleProfileResolver.LABEL_CONTRACT, "2");

        ConsoleProfile profile = ConsoleProfileResolver.fromLabels(labels, ConsoleProfileResolver.contractV1());

        assertEquals("/api/health", profile.healthPath());
    }

    @Test
    void labelsWithoutTheOptInAreIgnored() {
        Map<String, String> labels = Map.of(
                ConsoleProfileResolver.LABEL_PREFIX + "health-path", "/v2/health");

        assertEquals("/api/health",
                ConsoleProfileResolver.fromLabels(labels, ConsoleProfileResolver.contractV1()).healthPath());
    }

    @Test
    void anUnusablePortLabelIsIgnoredRatherThanBreakingTheStart() {
        // A typo in one label should cost the label, not the console.
        for (String port : new String[]{"eighty-eighty", "0", "70000", "8080 "}) {
            ConsoleProfile profile = ConsoleProfileResolver.fromLabels(
                    contractLabels("port", port), ConsoleProfileResolver.contractV1());

            assertEquals("8080 ".equals(port) ? 8080 : 4500, profile.internalPort(), port);
        }
    }

    @Test
    void aDeclaredButEmptyReadyFieldLabelMeansAnyTwoHundredIsReady() {
        // The one label where "declared as empty" differs from "not declared": it is how a console
        // with a plain liveness endpoint says it has no field worth reading.
        ConsoleProfile profile = ConsoleProfileResolver.fromLabels(
                contractLabels("health-ready-field", ""), ConsoleProfileResolver.contractV1());

        assertFalse(profile.hasReadyField());
    }

    @Test
    void labelsRefineTheBuiltInProfileTheyDoNotReplaceIt() {
        // A floci-ui image that starts declaring only its health path keeps the rest of its profile.
        ConsoleProfile profile = ConsoleProfileResolver.fromLabels(
                contractLabels("health-path", "/api/health"),
                ConsoleProfileResolver.builtIn("floci/floci-ui:latest"));

        assertEquals("/api/health", profile.healthPath());
        assertEquals("runtime", profile.healthReadyField());
    }

    // --- layer 1: explicit configuration ---

    @Test
    void configurationBeatsLabelsWhichBeatTheBuiltInProfile() {
        nothingConfigured();
        when(ui.statusPath()).thenReturn(Optional.of("/operator/health"));
        Map<String, String> labels = contractLabels(
                "health-path", "/label/health",
                "health-ready-field", "state");

        ConsoleProfile profile = ConsoleProfileResolver.resolve("floci/floci-ui:latest", Optional.of(labels), ui);

        assertEquals("/operator/health", profile.healthPath(), "configuration wins");
        assertEquals("state", profile.healthReadyField(), "an unconfigured field falls through to the label");
    }

    @Test
    void configuringTheReadyFieldToNoneTurnsItOff() {
        nothingConfigured();
        when(ui.statusReadyField()).thenReturn(Optional.of("none"));

        assertFalse(ConsoleProfileResolver.withOverrides(ui, ConsoleProfileResolver.contractV1())
                .hasReadyField());
    }

    @Test
    void anEmptyConfiguredValueIsTreatedAsUnsetRatherThanAsAnEmptyPath() {
        // An environment variable set to nothing reaches SmallRye as an absent property; treating a
        // blank the same way keeps the two spellings from meaning different things.
        nothingConfigured();
        when(ui.statusPath()).thenReturn(Optional.of("   "));

        assertEquals("/api/clouds/aws/status",
                ConsoleProfileResolver.resolve("floci/floci-ui:latest", Optional.empty(), ui).healthPath());
    }

    @Test
    void unreadableLabelsFallThroughToTheProfileRatherThanFailing() {
        nothingConfigured();

        ConsoleProfile profile = ConsoleProfileResolver.resolve("floci/floci-ui:latest", Optional.empty(), ui);

        assertEquals("/api/clouds/aws/status", profile.healthPath());
        assertTrue(profile.hasReadyField());
    }
}
