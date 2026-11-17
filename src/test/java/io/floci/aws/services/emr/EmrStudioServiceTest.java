package io.floci.aws.services.emr;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Vpc;
import io.floci.aws.services.emr.model.EmrStudio;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A Studio's place in its listing, which no deleted Studio's can take, through {@link EmrStudioService}. */
class EmrStudioServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final MutableClock clock = new MutableClock();
    private EmrStudioService studios;

    @BeforeEach
    void setUp() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-1");
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-1");
        group.setVpcId("vpc-1");
        when(ec2.describeVpcs(eq(REGION), anyList(), any())).thenReturn(List.of(vpc));
        when(ec2.describeSubnets(eq(REGION), anyList(), any())).thenReturn(List.of(subnet));
        when(ec2.describeSecurityGroups(eq(REGION), anyList(), anyList(), any())).thenReturn(List.of(group));
        studios = new EmrStudioService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                new RegionResolver(REGION, ACCOUNT), ec2, mock(SsoAdminService.class),
                mock(IdentityStoreService.class), clock, "http://localhost:4566");
    }

    @Test
    void aStudioMadeAfterTheNewestIsDeletedListsAfterEveryEarlierOne() {
        String first = studios.createStudio(studio("first")).getStudioId();
        EmrStudio newest = studios.createStudio(studio("newest"));
        studios.deleteStudio(newest.getStudioId());

        EmrStudio replacement = studios.createStudio(studio("replacement"));

        assertTrue(replacement.getSequence() > newest.getSequence(),
                replacement.getSequence() + " after " + newest.getSequence());
        assertEquals(List.of(first, replacement.getStudioId()), studios.listStudios(null).items().stream()
                .map(EmrStudio::getStudioId).toList());
    }

    private static EmrStudio studio(String name) {
        EmrStudio studio = new EmrStudio();
        studio.setName(name);
        studio.setAuthMode("IAM");
        studio.setVpcId("vpc-1");
        studio.setSubnetIds(List.of("subnet-1"));
        studio.setServiceRole("EMRStudio_Service_Role");
        studio.setWorkspaceSecurityGroupId("sg-1");
        studio.setEngineSecurityGroupId("sg-1");
        studio.setDefaultS3Location("s3://bucket/studio/");
        return studio;
    }
}
