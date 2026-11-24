package io.floci.aws.services.eks.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticLoadBalancingTest {

    @Test
    void loadBalancingIsOnOnlyWhenEnabledIsTrue() {
        assertTrue(ElasticLoadBalancing.loadBalancingOn(new ElasticLoadBalancing(true)));
        assertFalse(ElasticLoadBalancing.loadBalancingOn(new ElasticLoadBalancing(false)));
        assertFalse(ElasticLoadBalancing.loadBalancingOn(new ElasticLoadBalancing(null)));
        assertFalse(ElasticLoadBalancing.loadBalancingOn(null));
    }
}
