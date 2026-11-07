package io.floci.aws.services.eventbridge;

import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventBridgeResourcePolicyProviderTest {

    private static final String OWNER = "111122223333";
    private static final String BUS = "arn:aws:events:us-east-1:" + OWNER + ":event-bus/orders";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private EventBridgeService eventBridgeService;
    private EventBridgeResourcePolicyProvider provider;

    @BeforeEach
    void setUp() {
        eventBridgeService = mock(EventBridgeService.class);
        when(eventBridgeService.findBusPolicy(anyString())).thenReturn(Optional.empty());
        provider = new EventBridgeResourcePolicyProvider(eventBridgeService);
    }

    @Test
    void aBusComesWithItsPolicyAndTheAccountInItsArn() {
        when(eventBridgeService.findBusPolicy(BUS)).thenReturn(Optional.of(POLICY));

        assertEquals(List.of(new ResourcePolicy(POLICY, OWNER)), provider.getResourcePolicies("events", BUS));
    }

    @Test
    void aBusWithNoPolicyStillHasItsOwner() {
        assertEquals(List.of(new ResourcePolicy(null, OWNER)), provider.getResourcePolicies("events", BUS));
    }

    @Test
    void anythingThatIsNotOneBusIsLeftToOtherProviders() {
        String events = "arn:aws:events:us-east-1:" + OWNER + ":";
        assertTrue(provider.getResourcePolicies("events", "*").isEmpty());
        assertTrue(provider.getResourcePolicies("events", events + "event-bus/*").isEmpty());
        assertTrue(provider.getResourcePolicies("events", events + "rule/orders/r").isEmpty());
        assertTrue(provider.getResourcePolicies("events", "arn:aws:events:::event-bus/orders").isEmpty());
        assertTrue(provider.getResourcePolicies("sqs", "arn:aws:sqs:us-east-1:" + OWNER + ":orders").isEmpty());
    }

    @Test
    void malformedRegionalOwnersDoNotReadPolicies() {
        for (String arn : List.of(
                "arn:aws:events:us-east-1:123:event-bus/orders",
                "arn:aws:events:us-east-1:11112222333x:event-bus/orders",
                "arn:aws:events:us-east-1:１１１１２２２２３３３３:event-bus/orders",
                "arn:aws:events::111122223333:event-bus/orders",
                "arn:aws:events: :111122223333:event-bus/orders")) {
            assertTrue(provider.getResourcePolicies("states", arn).isEmpty(), arn);
        }
        assertTrue(provider.getResourcePolicies("states", null).isEmpty());
        verifyNoInteractions(eventBridgeService);
    }

    @Test
    void aForeignCredentialScopeStillReadsThePartitionResourceOwner() {
        String arn = "arn:aws-us-gov:events:us-gov-west-1:" + OWNER + ":event-bus/orders";
        when(eventBridgeService.findBusPolicy(arn)).thenReturn(Optional.of(POLICY));

        assertEquals(List.of(new ResourcePolicy(POLICY, OWNER)), provider.getResourcePolicies("states", arn));
    }
}
