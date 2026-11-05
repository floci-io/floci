package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqsResourcePolicyProviderTest {

    private static final String OWNER = "111122223333";
    private static final String QUEUE = "arn:aws:sqs:us-east-1:" + OWNER + ":orders";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private SqsService sqsService;
    private SqsResourcePolicyProvider provider;

    @BeforeEach
    void setUp() {
        sqsService = mock(SqsService.class);
        when(sqsService.findQueuePolicy(anyString())).thenReturn(Optional.empty());
        provider = new SqsResourcePolicyProvider(sqsService);
    }

    @Test
    void aQueueComesWithItsPolicyAndTheAccountInItsArn() {
        when(sqsService.findQueuePolicy(QUEUE)).thenReturn(Optional.of(POLICY));

        assertEquals(List.of(new ResourcePolicy(POLICY, OWNER)), provider.getResourcePolicies("sqs", QUEUE));
    }

    @Test
    void aQueueWithNoPolicyStillHasItsOwner() {
        assertEquals(List.of(new ResourcePolicy(null, OWNER)), provider.getResourcePolicies("sqs", QUEUE));
    }

    @Test
    void anythingThatIsNotOneQueueIsLeftToOtherProviders() {
        assertTrue(provider.getResourcePolicies("sqs", "*").isEmpty());
        assertTrue(provider.getResourcePolicies("sqs", "arn:aws:sqs:us-east-1:" + OWNER + ":*").isEmpty());
        assertTrue(provider.getResourcePolicies("sns", "arn:aws:sns:us-east-1:" + OWNER + ":topic").isEmpty());
    }
}
