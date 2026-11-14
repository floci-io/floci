package io.floci.aws.services.redshift;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftScheduledActionAuthorizerTest {
    private static final String ROLE = "arn:aws:iam::123456789012:role/scheduler";

    private ServiceRoleAuthorizer roles;
    private RedshiftScheduledActionAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        roles = mock(ServiceRoleAuthorizer.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.partitionForRegion("eu-west-1")).thenReturn("aws");
        authorizer = new RedshiftScheduledActionAuthorizer(roles, regionResolver);
    }

    @Test
    void theRoleIsCheckedForTheActionOfTheOperationOnTheClusterOfItsRegion() {
        when(roles.refusal(any(), any(), any(), any(), anyList())).thenReturn(Optional.of("denied"));

        Optional<String> refusal = authorizer.refusal(ROLE, "123456789012", "eu-west-1", "PauseCluster", "analytics");

        assertEquals(Optional.of("denied"), refusal);
        ArgumentCaptor<List<Permission>> permissions = ArgumentCaptor.forClass(List.class);
        verify(roles).refusal(eq(ROLE), eq("123456789012"), eq(ServicePrincipals.of("scheduler.redshift")), isNull(),
                permissions.capture());
        assertEquals(1, permissions.getValue().size());
        assertEquals("redshift:PauseCluster", permissions.getValue().get(0).action());
        assertEquals("arn:aws:redshift:eu-west-1:123456789012:cluster:analytics",
                permissions.getValue().get(0).resourceArn());
    }

    @Test
    void resumeIsCheckedAsItsOwnAction() {
        authorizer.refusal(ROLE, "123456789012", "eu-west-1", "ResumeCluster", "analytics");

        ArgumentCaptor<List<Permission>> permissions = ArgumentCaptor.forClass(List.class);
        verify(roles).refusal(any(), any(), any(), any(), permissions.capture());
        assertEquals("redshift:ResumeCluster", permissions.getValue().get(0).action());
    }

    @Test
    void anOperationTheTablesDoNotKnowIsRefusedInsteadOfCheckedOnNothing() {
        Optional<String> refusal = authorizer.refusal(ROLE, "123456789012", "eu-west-1", "NoSuchOperation", "analytics");

        assertTrue(refusal.isPresent());
    }
}
