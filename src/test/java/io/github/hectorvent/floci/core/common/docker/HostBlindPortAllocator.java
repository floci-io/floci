package io.github.hectorvent.floci.core.common.docker;

/**
 * A {@link PortAllocator} that skips the host probe, so a test sees the same ports free on any
 * machine and only the allocator's own reservations and Docker's answers decide which port is used.
 */
public class HostBlindPortAllocator extends PortAllocator {

    @Override
    public boolean isPortFree(int port) {
        return true;
    }
}
