package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers {@link SesService#resolveSuppressionReason(String, String)} — the per-recipient
 * lookup that publishSendEvents uses to map suppressed addresses to synthetic Bounce or
 * Complaint events.
 *
 * <p>The helper returns a reason only when the address is on the suppression list AND
 * the address's stored reason intersects {@link AccountSuppressionAttributes#getSuppressedReasons()}.
 */
class SesServiceSuppressionReasonTest {

    private static final String REGION = "us-east-1";

    private SesService service;
    private InMemoryStorage<String, SuppressedDestination> suppressionStore;
    private InMemoryStorage<String, AccountSuppressionAttributes> accountSuppressionStore;

    @BeforeEach
    void setUp() {
        SesServiceTestBuilder builder = SesServiceTestBuilder.create();
        suppressionStore = builder.suppressionStore();
        accountSuppressionStore = builder.accountSuppressionStore();
        service = builder.build();
    }

    @Test
    void notOnList_returnsNull() {
        // Default fresh account: suppressedReasons defaults to [BOUNCE, COMPLAINT], but the
        // address is not on the list, so resolution returns null.
        assertNull(service.resolveSuppressionReason("unknown@example.com", null, REGION));
    }

    @Test
    void onListAndReasonInAccountSettings_returnsReason() {
        service.putSuppressedDestination(REGION, "bouncer@example.com", "BOUNCE");
        // Account-level suppressedReasons defaults to [BOUNCE, COMPLAINT].
        assertEquals("BOUNCE", service.resolveSuppressionReason("bouncer@example.com", null, REGION));
    }

    @Test
    void onListButReasonNotInAccountSettings_returnsNull() {
        service.putSuppressedDestination(REGION, "complainer@example.com", "COMPLAINT");
        // Narrow the account settings to BOUNCE only.
        service.putAccountSuppressionAttributes(REGION, List.of("BOUNCE"));
        assertNull(service.resolveSuppressionReason("complainer@example.com", null, REGION));
    }

    @Test
    void accountSettingsEmpty_returnsNull() {
        service.putSuppressedDestination(REGION, "bouncer@example.com", "BOUNCE");
        // Disable account-level suppression by passing an empty list.
        service.putAccountSuppressionAttributes(REGION, new ArrayList<>());
        assertNull(service.resolveSuppressionReason("bouncer@example.com", null, REGION));
    }

    @Test
    void leadingTrailingWhitespaceIsNormalized() {
        service.putSuppressedDestination(REGION, "trim-me@example.com", "BOUNCE");
        // Caller may pass the recipient with surrounding whitespace (e.g. from a header).
        assertEquals("BOUNCE",
                service.resolveSuppressionReason("  trim-me@example.com  ", null, REGION));
    }

    @Test
    void nullOrBlankInput_returnsNull() {
        assertNull(service.resolveSuppressionReason(null, null, REGION));
        assertNull(service.resolveSuppressionReason("", null, REGION));
        assertNull(service.resolveSuppressionReason("   ", null, REGION));
    }
}
