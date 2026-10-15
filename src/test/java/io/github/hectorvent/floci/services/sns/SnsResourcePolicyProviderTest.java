package io.github.hectorvent.floci.services.sns;

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

class SnsResourcePolicyProviderTest {

    private static final String OWNER = "111122223333";
    private static final String TOPIC = "arn:aws:sns:us-east-1:" + OWNER + ":events";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private SnsService snsService;
    private SnsResourcePolicyProvider provider;

    @BeforeEach
    void setUp() {
        snsService = mock(SnsService.class);
        when(snsService.findTopicPolicy(anyString())).thenReturn(Optional.empty());
        provider = new SnsResourcePolicyProvider(snsService);
    }

    @Test
    void aTopicComesWithItsPolicyAndTheAccountInItsArn() {
        when(snsService.findTopicPolicy(TOPIC)).thenReturn(Optional.of(POLICY));

        assertEquals(List.of(new ResourcePolicy(POLICY, OWNER)), provider.getResourcePolicies("sns", TOPIC));
        assertEquals(List.of(new ResourcePolicy(POLICY, OWNER)), provider.getResourcePolicies("ses", TOPIC));
    }

    @Test
    void aTopicThatDoesNotExistStillHasItsOwner() {
        assertEquals(List.of(new ResourcePolicy(null, OWNER)), provider.getResourcePolicies("sns", TOPIC));
        assertEquals(List.of(new ResourcePolicy(null, OWNER)),
                provider.getResourcePolicies("sns", TOPIC + ".fifo"));
    }

    @Test
    void anythingThatIsNotATopicIsLeftToOtherProviders() {
        assertTrue(provider.getResourcePolicies("sns", "*").isEmpty());
        assertTrue(provider.getResourcePolicies("sns", "arn:aws:sns:us-east-1:" + OWNER + ":*").isEmpty());
        assertTrue(provider.getResourcePolicies("sns",
                TOPIC + ":6f1b3c2e-0d5a-4b8e-9c71-2a4d8e9f0b13").isEmpty());
        assertTrue(provider.getResourcePolicies("sns",
                "arn:aws:sns:us-east-1:" + OWNER + ":app/GCM/my-app").isEmpty());
        assertTrue(provider.getResourcePolicies("sqs", "arn:aws:sqs:us-east-1:" + OWNER + ":queue").isEmpty());
    }
}
