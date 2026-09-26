package io.github.hectorvent.floci.services.ecs.container;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsTaskLinkLocalAddressesTest {

    private static final String NETWORK = "floci-net";
    private static final String OTHER_NETWORK = "floci-other-net";
    private static final String TASK = "arn:aws:ecs:us-east-1:000000000000:task/cluster/abc";
    private static final String OTHER_TASK = "arn:aws:ecs:us-east-1:000000000000:task/cluster/def";

    private EcsTaskLinkLocalAddresses addresses;

    @BeforeEach
    void setUp() {
        addresses = new EcsTaskLinkLocalAddresses();
    }

    @Test
    void handsOutDistinctAddressesOnOneNetwork() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertTrue(seen.add(addresses.allocate(NETWORK, TASK)), "handed out a duplicate at " + i);
        }
    }

    @Test
    void neverHandsOutTheAddressTheProxyHolds() {
        // Walk past where .2 sits so the skip is actually exercised rather than assumed.
        for (int i = 0; i < 20; i++) {
            assertNotEquals(EcsTaskLinkLocalAddresses.CREDENTIALS_ENDPOINT_ADDRESS,
                    addresses.allocate(NETWORK, TASK));
        }
    }

    @Test
    void allocatesFromTheProxysOwnSubnetSteppingOverItsAddress() {
        assertEquals("169.254.170.1", addresses.allocate(NETWORK, TASK));
        // .2 belongs to the proxy, so the next one skips it.
        assertEquals("169.254.170.3", addresses.allocate(NETWORK, TASK));
        assertEquals("169.254.170.4", addresses.allocate(NETWORK, TASK));
    }

    @Test
    void separateNetworksReuseTheSameAddressesIndependently() {
        String first = addresses.allocate(NETWORK, TASK);
        String onOtherNetwork = addresses.allocate(OTHER_NETWORK, OTHER_TASK);

        // Different L2 segments: the same address on each is not a conflict.
        assertEquals(first, onOtherNetwork);
    }

    @Test
    void releasingATaskFreesItsAddressesForReuse() {
        String first = addresses.allocate(NETWORK, TASK);
        String second = addresses.allocate(NETWORK, TASK);
        String heldByAnotherTask = addresses.allocate(NETWORK, OTHER_TASK);

        addresses.releaseAll(TASK);

        // The other task's address stays held, so the freed ones come back before it.
        Set<String> reused = Set.of(addresses.allocate(NETWORK, TASK), addresses.allocate(NETWORK, TASK));
        assertEquals(Set.of(first, second), reused);
        assertNotEquals(heldByAnotherTask, addresses.allocate(NETWORK, TASK));
    }

    @Test
    void releasingAnUnknownTaskIsANoOp() {
        addresses.releaseAll("arn:aws:ecs:us-east-1:000000000000:task/cluster/never-allocated");
    }

    @Test
    void releasingOneTaskLeavesAnotherTasksAddressesAlone() {
        String mine = addresses.allocate(NETWORK, TASK);
        String theirs = addresses.allocate(NETWORK, OTHER_TASK);

        addresses.releaseAll(OTHER_TASK);

        Set<String> next = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            next.add(addresses.allocate(NETWORK, TASK));
        }
        assertTrue(next.contains(theirs), "the released address should be reusable");
        assertTrue(!next.contains(mine), "an address still held must not be handed out again");
    }
}
