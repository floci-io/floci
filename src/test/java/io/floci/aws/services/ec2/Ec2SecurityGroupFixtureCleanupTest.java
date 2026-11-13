package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Vpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Ec2SecurityGroupFixtureCleanupTest {
    private static final String REGION = "us-west-2";

    @Test
    void selfReferenceSetupFailureStillDeletesItsVpc() {
        Ec2SecurityGroupDeletionIntegrationTest fixture = fixture();
        IllegalStateException failure = new IllegalStateException("group creation failed");
        when(fixture.service.createVpc(REGION, "10.230.0.0/24", false)).thenReturn(vpc("vpc-owned"));
        when(fixture.service.createSecurityGroup(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, fixture::selfReferenceAllowsDeletionAndOwnRulesAreRemoved));
        fixture.cleanup.afterEach(mock(ExtensionContext.class));

        verify(fixture.service).deleteVpc(REGION, "vpc-owned");
        verify(fixture.service, never()).deleteSecurityGroup(anyString(), anyString());
    }

    @Test
    void missingDefaultSetupFailureDeletesCustomVpcBeforeRestoringDefault() {
        Ec2SecurityGroupDeletionIntegrationTest fixture = fixture();
        IllegalStateException failure = new IllegalStateException("group creation failed");
        when(fixture.service.createDefaultVpc(REGION)).thenReturn(vpc("vpc-default"));
        when(fixture.service.createVpc(REGION, "10.229.0.0/24", false)).thenReturn(vpc("vpc-custom"));
        when(fixture.service.createSecurityGroup(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, fixture::nameWithoutDefaultVpcCannotSelectACustomVpcGroup));
        fixture.cleanup.afterEach(mock(ExtensionContext.class));

        InOrder order = inOrder(fixture.service);
        order.verify(fixture.service).createDefaultVpc(REGION);
        order.verify(fixture.service).deleteVpc(REGION, "vpc-default");
        order.verify(fixture.service).deleteVpc(REGION, "vpc-custom");
        order.verify(fixture.service).createDefaultVpc(REGION);
    }

    @Test
    void failedFirstAllocationDoesNotDeleteUnownedResources() {
        Ec2SecurityGroupDeletionIntegrationTest fixture = fixture();
        IllegalStateException failure = new IllegalStateException("VPC creation failed");
        when(fixture.service.createVpc(anyString(), anyString(), anyBoolean())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, fixture::selfReferenceAllowsDeletionAndOwnRulesAreRemoved));
        fixture.cleanup.afterEach(mock(ExtensionContext.class));

        verify(fixture.service, never()).deleteVpc(anyString(), anyString());
        verify(fixture.service, never()).deleteSecurityGroup(anyString(), anyString());
    }

    private static Ec2SecurityGroupDeletionIntegrationTest fixture() {
        Ec2SecurityGroupDeletionIntegrationTest fixture = new Ec2SecurityGroupDeletionIntegrationTest();
        fixture.service = mock(Ec2Service.class);
        return fixture;
    }

    private static Vpc vpc(String id) {
        Vpc vpc = new Vpc();
        vpc.setVpcId(id);
        return vpc;
    }
}
