package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorAddressesTest {

    @Test
    void recognisesSuccess() {
        assertTrue(SimulatorAddresses.isSuccess("success@simulator.amazonses.com"));
        assertTrue(SimulatorAddresses.isSuccess("SUCCESS@SIMULATOR.AMAZONSES.COM"));
        assertTrue(SimulatorAddresses.isSuccess("  success@simulator.amazonses.com  "));
    }

    @Test
    void recognisesBounce() {
        assertTrue(SimulatorAddresses.isBounce("bounce@simulator.amazonses.com"));
    }

    @Test
    void recognisesComplaint() {
        assertTrue(SimulatorAddresses.isComplaint("complaint@simulator.amazonses.com"));
    }

    @Test
    void recognisesSuppressionList() {
        assertTrue(SimulatorAddresses.isSuppressionList("suppressionlist@simulator.amazonses.com"));
    }

    @Test
    void recognisesLabelledAddresses() {
        // The simulator supports a +label subaddress so senders can distinguish test messages.
        assertTrue(SimulatorAddresses.isBounce("bounce+order-123@simulator.amazonses.com"));
        assertTrue(SimulatorAddresses.isSuccess("success+anything@simulator.amazonses.com"));
        assertTrue(SimulatorAddresses.isComplaint("complaint+abc@simulator.amazonses.com"));
        assertTrue(SimulatorAddresses.isSuppressionList("suppressionlist+x@simulator.amazonses.com"));
        // Label with dots, an empty label, and an upper-case type are all still recognised.
        assertTrue(SimulatorAddresses.isBounce("bounce+a.b.c@simulator.amazonses.com"));
        assertTrue(SimulatorAddresses.isBounce("bounce+@simulator.amazonses.com"));
        assertTrue(SimulatorAddresses.isBounce("Bounce+Label@simulator.amazonses.com"));
    }

    @Test
    void onlyPlusIsALabelSeparator() {
        // A hyphen is part of the local part, not a label separator, so this is not a bounce.
        assertFalse(SimulatorAddresses.isBounce("bounce-mylabel@simulator.amazonses.com"));
    }

    @Test
    void labelDoesNotMakeUnknownTypesMatch() {
        // An unknown type with a label is not any simulator event.
        assertFalse(SimulatorAddresses.isSuccess("delivery+x@simulator.amazonses.com"));
        assertFalse(SimulatorAddresses.isBounce("delivery+x@simulator.amazonses.com"));
    }

    @Test
    void rejectsRegularAddresses() {
        assertFalse(SimulatorAddresses.isSuccess("user@example.com"));
        assertFalse(SimulatorAddresses.isBounce("user@example.com"));
        assertFalse(SimulatorAddresses.isComplaint("user@example.com"));
        assertFalse(SimulatorAddresses.isSuppressionList("user@example.com"));
        // A +label on a non-simulator domain must not match either.
        assertFalse(SimulatorAddresses.isBounce("bounce+label@example.com"));
    }

    @Test
    void handlesNullSafely() {
        assertFalse(SimulatorAddresses.isSuccess(null));
        assertFalse(SimulatorAddresses.isBounce(null));
        assertFalse(SimulatorAddresses.isComplaint(null));
        assertFalse(SimulatorAddresses.isSuppressionList(null));
    }
}
