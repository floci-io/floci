package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted account domain. Like the receipt-rule step, the
 * service is constructed with just its own store — no 14-argument SesService needed.
 */
class SesAccountServiceTest {

    private static final String REGION = "us-east-1";
    private SesAccountService service;

    @BeforeEach
    void setUp() {
        service = new SesAccountService(new InMemoryStorage<>());
    }

    @Test
    void sendingEnabled_defaultsTrue() {
        assertTrue(service.isAccountSendingEnabled(REGION));
    }

    @Test
    void setSendingEnabled_roundTrips() {
        service.setAccountSendingEnabled(REGION, false);
        assertFalse(service.isAccountSendingEnabled(REGION));

        service.setAccountSendingEnabled(REGION, true);
        assertTrue(service.isAccountSendingEnabled(REGION));
    }

    @Test
    void sendingEnabled_isPerRegion() {
        service.setAccountSendingEnabled(REGION, false);
        assertTrue(service.isAccountSendingEnabled("eu-west-1"));
    }
}
