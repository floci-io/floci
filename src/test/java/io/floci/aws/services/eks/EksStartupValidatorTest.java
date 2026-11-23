package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EksStartupValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://aws.example.test", "aws.example.test:6443", "a.example.test,b.example.test"})
    void invalidEndpointHostIsRejectedByStartupObserver(String value) {
        EksStartupValidator validator = validator(Optional.of(value));
        assertThrows(IllegalArgumentException.class, () -> validator.onStart(new StartupEvent()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "aws.example.test", "192.168.8.24", "[fd00::24]"})
    void validOrUnsetEndpointHostIsAcceptedByStartupObserver(String value) {
        EksStartupValidator validator = validator(value.isEmpty() ? Optional.empty() : Optional.of(value));
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @Test
    void invalidEndpointHostIsIgnoredWhenEksIsMetadataOnly() {
        EksStartupValidator disabled = validator(Optional.of("https://aws.example.test"), false, false);
        EksStartupValidator mock = validator(Optional.of("https://aws.example.test"), true, true);
        assertDoesNotThrow(() -> disabled.onStart(new StartupEvent()));
        assertDoesNotThrow(() -> mock.onStart(new StartupEvent()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 17})
    void aPodPrefixCountOutOfRangeIsRejectedWithTheVpcPodNetworkOn(int prefixes) {
        EksStartupValidator validator = vpcPodNetworkValidator(true, prefixes);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> validator.onStart(new StartupEvent()));
        assertTrue(refused.getMessage().contains("floci.services.eks.vpc-pod-prefixes"), refused.getMessage());
    }

    @Test
    void aPodPrefixCountIsCheckedOnlyWithTheVpcPodNetworkOn() {
        assertDoesNotThrow(() -> vpcPodNetworkValidator(true, 1).onStart(new StartupEvent()));
        assertDoesNotThrow(() -> vpcPodNetworkValidator(true, 16).onStart(new StartupEvent()));
        assertDoesNotThrow(() -> vpcPodNetworkValidator(false, 0).onStart(new StartupEvent()));
    }

    private static EksStartupValidator vpcPodNetworkValidator(boolean vpcPodNetwork, int prefixes) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eks().endpointHost()).thenReturn(Optional.empty());
        when(config.services().eks().enabled()).thenReturn(true);
        when(config.services().eks().vpcPodNetwork()).thenReturn(vpcPodNetwork);
        when(config.services().eks().vpcPodPrefixes()).thenReturn(prefixes);
        return new EksStartupValidator(config);
    }

    private static EksStartupValidator validator(Optional<String> endpointHost) {
        return validator(endpointHost, true, false);
    }

    private static EksStartupValidator validator(Optional<String> endpointHost, boolean enabled, boolean mock) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eks().endpointHost()).thenReturn(endpointHost);
        when(config.services().eks().enabled()).thenReturn(enabled);
        when(config.services().eks().mock()).thenReturn(mock);
        return new EksStartupValidator(config);
    }
}
