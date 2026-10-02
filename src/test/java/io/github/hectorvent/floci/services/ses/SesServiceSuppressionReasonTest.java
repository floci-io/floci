package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link SesService#collectSuppressedReasons}, the per-send lookup that filters the relay
 * and maps suppressed recipients to synthetic Bounce or Complaint events.
 *
 * <p>An address is reported only when it is on the suppression list and its stored reason
 * intersects {@link AccountSuppressionAttributes#getSuppressedReasons()}.
 */
class SesServiceSuppressionReasonTest {

    private static final String REGION = "us-east-1";

    private SesService service;
    private SesSuppressionService suppression;
    private InMemoryStorage<String, SuppressedDestination> suppressionStore;
    private InMemoryStorage<String, AccountSuppressionAttributes> accountSuppressionStore;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        suppressionStore = builder.suppressionStore();
        accountSuppressionStore = builder.accountSuppressionStore();
        service = builder.build();
        suppression = builder.suppressionService();
    }

    @Test
    void notOnList_returnsEmpty() {
        // Default fresh account: suppressedReasons defaults to [BOUNCE, COMPLAINT], but the
        // address is not on the list, so nothing is reported.
        assertEquals(Map.of(), reasons("unknown@example.com"));
    }

    @Test
    void onListAndReasonInAccountSettings_returnsReason() {
        suppression.putSuppressedDestination(REGION, "bouncer@example.com", "BOUNCE");
        // Account-level suppressedReasons defaults to [BOUNCE, COMPLAINT].
        assertEquals(Map.of("bouncer@example.com", "BOUNCE"), reasons("bouncer@example.com"));
    }

    @Test
    void onListButReasonNotInAccountSettings_returnsEmpty() {
        suppression.putSuppressedDestination(REGION, "complainer@example.com", "COMPLAINT");
        // Narrow the account settings to BOUNCE only.
        suppression.putAccountSuppressionAttributes(REGION, List.of("BOUNCE"));
        assertEquals(Map.of(), reasons("complainer@example.com"));
    }

    @Test
    void accountSettingsEmpty_returnsEmpty() {
        suppression.putSuppressedDestination(REGION, "bouncer@example.com", "BOUNCE");
        // Disable account-level suppression by passing an empty list.
        suppression.putAccountSuppressionAttributes(REGION, new ArrayList<>());
        assertEquals(Map.of(), reasons("bouncer@example.com"));
    }

    @Test
    void leadingTrailingWhitespaceIsNormalized() {
        suppression.putSuppressedDestination(REGION, "trim-me@example.com", "BOUNCE");
        // Caller may pass the recipient with surrounding whitespace (e.g. from a header).
        assertEquals(Map.of("  trim-me@example.com  ", "BOUNCE"), reasons("  trim-me@example.com  "));
    }

    @Test
    void nullOrBlankInput_returnsEmpty() {
        assertEquals(Map.of(), service.collectSuppressedReasons(Arrays.asList(null, "", "   "), null, REGION));
        assertEquals(Map.of(), service.collectSuppressedReasons(null, null, REGION));
    }

    private Map<String, String> reasons(String address) {
        return service.collectSuppressedReasons(List.of(address), null, REGION);
    }
}
