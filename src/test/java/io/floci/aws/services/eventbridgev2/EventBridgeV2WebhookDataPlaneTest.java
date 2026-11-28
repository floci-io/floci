package io.floci.aws.services.eventbridgev2;

import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EventBridgeV2WebhookDataPlaneTest {
    private static final String ID = "abcdefghijklmnopqrstuvwxyz234567abcdef";

    @Test
    void onlyTheMintedHostShapeIsClaimed() {
        assertEquals(Optional.of(ID), EventBridgeV2WebhookDataPlane.hostId(ID + ".c01.events-webhook.us-east-1.localhost:4566"));
        assertEquals(Optional.of(ID), EventBridgeV2WebhookDataPlane.hostId(ID + ".c01.events-webhook.us-east-1.on.aws"));
        assertEquals(Optional.of(ID), EventBridgeV2WebhookDataPlane.hostId(
                ID.toUpperCase() + ".C01.EVENTS-WEBHOOK.US-EAST-1.LOCALHOST:4566"));
        assertTrue(EventBridgeV2WebhookDataPlane.hostId(ID + ".c02.events-webhook.us-east-1.localhost").isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId(ID + ".c01.events-webhook.localhost").isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId("b" + ID.substring(1) + ".c01.events-webhook.us-east-1.localhost")
                .isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId("a" + ID.substring(2) + ".c01.events-webhook.us-east-1.localhost")
                .isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId(ID + "1.c01.events-webhook.us-east-1.localhost").isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId("my.events-webhook.archive.localhost").isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId("localhost:4566").isEmpty());
        assertTrue(EventBridgeV2WebhookDataPlane.hostId(null).isEmpty());
    }
}
