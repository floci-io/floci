package io.github.hectorvent.floci.services.iam;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamResourceTagActionsTest {

    @Test
    void theVendoredTableCarriesSingleActions() {
        assertTrue(IamResourceTagActions.supports("sqs:SendMessage"));
        assertTrue(IamResourceTagActions.supports("sqs:ReceiveMessage"));
        assertTrue(IamResourceTagActions.supports("sns:Publish"));
        assertTrue(IamResourceTagActions.supports("sns:Subscribe"));
    }

    @Test
    void theBatchOperationsAreNotActions() {
        assertFalse(IamResourceTagActions.supports("sqs:SendMessageBatch"));
        assertFalse(IamResourceTagActions.supports("sns:PublishBatch"));
    }

    @Test
    void anActionWithNoTaggedResourceIsAbsent() {
        assertFalse(IamResourceTagActions.supports("sqs:ListQueues"));
        assertFalse(IamResourceTagActions.supports("dynamodb:GetItem"));
        assertFalse(IamResourceTagActions.supports(null));
    }

    @Test
    void parseCollectsEveryNamespace() throws Exception {
        Set<String> actions = IamResourceTagActions.parse(new ByteArrayInputStream("""
                {"namespaces":{"sns":["sns:Publish"],"sqs":["sqs:SendMessage"]}}
                """.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Set.of("sns:Publish", "sqs:SendMessage"), actions);
    }

    @Test
    void parseRejectsAnActionOutsideItsNamespace() {
        assertThrows(IllegalStateException.class, () -> IamResourceTagActions.parse(
                new ByteArrayInputStream("""
                        {"namespaces":{"sqs":["sns:Publish"]}}
                        """.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void parseRejectsAnEmptyTable() {
        assertThrows(IllegalStateException.class, () -> IamResourceTagActions.parse(
                new ByteArrayInputStream("{\"namespaces\":{}}".getBytes(StandardCharsets.UTF_8))));
    }
}
