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
    void rejectsRegularAddresses() {
        assertFalse(SimulatorAddresses.isSuccess("user@example.com"));
        assertFalse(SimulatorAddresses.isBounce("user@example.com"));
        assertFalse(SimulatorAddresses.isComplaint("user@example.com"));
        assertFalse(SimulatorAddresses.isSuppressionList("user@example.com"));
    }

    @Test
    void handlesNullSafely() {
        assertFalse(SimulatorAddresses.isSuccess(null));
        assertFalse(SimulatorAddresses.isBounce(null));
        assertFalse(SimulatorAddresses.isComplaint(null));
        assertFalse(SimulatorAddresses.isSuppressionList(null));
    }
}
