package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.elb.ElbClassicService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IAM's lookup of the tags Elastic Load Balancing holds, routed to the version that keeps the resource. */
class ElbStoredResourceArnsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ELB = "arn:aws:elasticloadbalancing:us-east-1:000000000000:";

    private final ElbV2Service elbV2 = mock(ElbV2Service.class);
    private final ElbClassicService classic = mock(ElbClassicService.class);
    private final ElbStoredResourceArns lookup =
            new ElbStoredResourceArns(elbV2, classic, new RegionResolver(REGION, ACCOUNT));

    /**
     * A version 2 resource's ARN reads the tags version 2 keeps under it, and a Classic Load Balancer's reads the load
     * balancer of its name in its Region, a tag without a value as an empty one.
     */
    @Test
    void storedTagsReadTheVersionThatKeepsTheResource() {
        String targetGroup = ELB + "targetgroup/web/0123456789abcdef";
        String listener = ELB + "listener/app/web/0123456789abcdef/fedcba9876543210";
        when(elbV2.tagsInAccount(ACCOUNT, targetGroup)).thenReturn(Map.of("team", "data"));
        when(elbV2.tagsInAccount(ACCOUNT, listener)).thenReturn(Map.of("team", "ops"));
        Map<String, String> noValue = new HashMap<>();
        noValue.put("owner", null);
        noValue.put("team", "web");
        when(classic.tagsInAccount(ACCOUNT, REGION, "legacy")).thenReturn(noValue);

        assertEquals("elasticloadbalancing", lookup.namespace());
        assertEquals(Map.of("team", "data"), lookup.storedTags(targetGroup));
        assertEquals(Map.of("team", "ops"), lookup.storedTags(listener));
        assertEquals(Map.of("owner", "", "team", "web"), lookup.storedTags(ELB + "loadbalancer/legacy"));
    }

    /**
     * An ARN of another account, another service, or a Classic Load Balancer's ARN without a Region or in another
     * partition than its Region's reads nothing, and neither version is asked.
     */
    @Test
    void storedTagsReadNothingForAnArnFlociDoesNotGive() {
        assertTrue(lookup.storedTags(ELB.replace(ACCOUNT, "111122223333") + "loadbalancer/legacy").isEmpty());
        assertTrue(lookup.storedTags(ELB.replace(ACCOUNT, "111122223333") + "targetgroup/web/0123456789abcdef")
                .isEmpty());
        assertTrue(lookup.storedTags("arn:aws:ec2:us-east-1:000000000000:instance/i-0123456789abcdef0").isEmpty());
        assertTrue(lookup.storedTags("arn:aws:elasticloadbalancing::000000000000:loadbalancer/legacy").isEmpty());
        assertTrue(lookup.storedTags("arn:aws-cn:elasticloadbalancing:us-east-1:000000000000:loadbalancer/legacy")
                .isEmpty());
        verify(classic, never()).tagsInAccount(anyString(), anyString(), anyString());
        verify(elbV2, never()).tagsInAccount(anyString(), anyString());
    }
}
