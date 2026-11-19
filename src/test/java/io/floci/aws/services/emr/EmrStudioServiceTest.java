package io.floci.aws.services.emr;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Vpc;
import io.floci.aws.services.emr.model.EmrStudio;
import io.floci.aws.services.emr.model.EmrStudioSessionMapping;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Studio's place in its listing, which no deleted Studio's can take, and the listings' markers, which resume only
 * their own account's and Region's, through {@link EmrStudioService}.
 */
class EmrStudioServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_REGION = "eu-west-1";
    private static final String OTHER_ACCOUNT = "111111111111";

    private final MutableClock clock = new MutableClock();
    // One store under every service here, as the services of one Floci share theirs.
    private final AccountAwareStorageBackend<EmrStudio> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private Ec2Service ec2;
    private EmrStudioService studios;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
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
        studios = service(REGION, ACCOUNT);
    }

    // The service as a request in region of account reaches it.
    private EmrStudioService service(String region, String account) {
        return new EmrStudioService(store, new RegionResolver(region, account), ec2, mock(SsoAdminService.class),
                mock(IdentityStoreService.class), clock, "http://localhost:4566");
    }

    @Test
    void aStudiosListMarkerResumesOnlyTheListingOfItsAccountAndRegion() {
        for (int made = 0; made < 51; made++) {
            studios.createStudio(studio("studio-" + made));
        }
        String marker = studios.listStudios(null).nextToken();

        assertNotNull(marker);
        assertEquals(1, studios.listStudios(marker).items().size());
        assertRefused(() -> service(OTHER_REGION, ACCOUNT).listStudios(marker));
        assertRefused(() -> service(REGION, OTHER_ACCOUNT).listStudios(marker));
    }

    @Test
    void aSessionMappingsListMarkerResumesOnlyTheListingOfItsAccountRegionAndFilters() {
        EmrStudio studio = new EmrStudio();
        studio.setStudioId("es-MAPPINGS");
        studio.setRegion(REGION);
        studio.setAuthMode("SSO");
        studio.setSequence(1);
        for (int made = 0; made < 51; made++) {
            EmrStudioSessionMapping mapping = new EmrStudioSessionMapping();
            mapping.setIdentityId("user-" + made);
            mapping.setIdentityType("USER");
            mapping.setSequence(made + 1);
            studio.getSessionMappings().add(mapping);
        }
        store.put(studio.getStudioId(), studio);
        String marker = studios.listSessionMappings(null, null, null).nextToken();

        assertNotNull(marker);
        assertEquals(1, studios.listSessionMappings(null, null, marker).items().size());
        assertRefused(() -> service(OTHER_REGION, ACCOUNT).listSessionMappings(null, null, marker));
        assertRefused(() -> service(REGION, OTHER_ACCOUNT).listSessionMappings(null, null, marker));
        assertRefused(() -> studios.listSessionMappings(null, "USER", marker));
    }

    private static void assertRefused(Executable listing) {
        AwsException refused = assertThrows(AwsException.class, listing);
        assertEquals("InvalidRequestException", refused.getErrorCode());
        assertEquals("Invalid Marker.", refused.getMessage());
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
