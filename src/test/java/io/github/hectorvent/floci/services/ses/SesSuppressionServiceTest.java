package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted suppression domain. New facet: this closes the
 * shared-helper deferral from the account step — account suppression and the suppression list both
 * live here and share the (now private) reason validation, exercised by both paths below. Two stores,
 * one service.
 */
class SesSuppressionServiceTest {

    private static final String REGION = "us-east-1";
    private SesSuppressionService service;

    @BeforeEach
    void setUp() {
        service = new SesSuppressionService(new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    @Test
    void accountSuppression_defaultsToBounceAndComplaint() {
        assertEquals(List.of("BOUNCE", "COMPLAINT"),
                service.getAccountSuppressionAttributes(REGION).getSuppressedReasons());
    }

    @Test
    void putAccountSuppression_rejectsInvalidReason() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putAccountSuppressionAttributes(REGION, List.of("NONSENSE")));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void suppressedDestination_putGetDelete_roundTrips() {
        service.putSuppressedDestination(REGION, "a@example.com", "BOUNCE");
        SuppressedDestination got = service.getSuppressedDestination(REGION, "a@example.com");
        assertEquals("BOUNCE", got.getReason());

        service.deleteSuppressedDestination(REGION, "a@example.com");
        assertTrue(service.findSuppressedDestination(REGION, "a@example.com").isEmpty());
    }

    @Test
    void putSuppressedDestination_rejectsInvalidReason() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.putSuppressedDestination(REGION, "a@example.com", "NONSENSE"));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void findSuppressedDestination_normalizesDomainCase() {
        service.putSuppressedDestination(REGION, "User@Example.COM", "COMPLAINT");
        // Domain is canonicalized to lower case, so a differently-cased domain resolves the entry.
        assertTrue(service.findSuppressedDestination(REGION, "User@example.com").isPresent());
    }
}
