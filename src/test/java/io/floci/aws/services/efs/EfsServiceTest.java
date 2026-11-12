package io.floci.aws.services.efs;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.efs.model.CreateFileSystemRequest;
import io.floci.aws.services.efs.model.CreateMountTargetRequest;
import io.floci.aws.services.efs.model.IpAddressType;
import io.floci.aws.services.efs.model.MountTarget;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EfsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private EfsService service;
    private String fileSystemId;

    @BeforeEach
    void setUp() {
        service = new EfsService(new InMemoryStorageFactory(ACCOUNT), new RegionResolver(REGION, ACCOUNT));
        fileSystemId = service.createFileSystem(new CreateFileSystemRequest(), REGION).getFileSystemId();
    }

    private CreateMountTargetRequest request(String subnetId, IpAddressType type) {
        CreateMountTargetRequest request = new CreateMountTargetRequest();
        request.setFileSystemId(fileSystemId);
        request.setSubnetId(subnetId);
        request.setIpAddressType(type);
        return request;
    }

    @Test
    void mountTargetWithoutAnAddressTypeGetsTheDefaultIpv4Address() {
        MountTarget created = service.createMountTarget(request("subnet-1", null), REGION);

        assertEquals("10.0.0.10", created.getIpAddress());
    }

    @Test
    void dualStackMountTargetGetsTheDefaultIpv4Address() {
        MountTarget created = service.createMountTarget(request("subnet-1", IpAddressType.DUAL_STACK), REGION);

        assertEquals("10.0.0.10", created.getIpAddress());
    }

    @Test
    void ipv6OnlyMountTargetHasNoIpv4Address() {
        MountTarget created = service.createMountTarget(request("subnet-1", IpAddressType.IPV6_ONLY), REGION);

        assertNull(created.getIpAddress());
        assertEquals(IpAddressType.IPV6_ONLY, created.getIpAddressType());
    }

    @Test
    void anAddressTheCallerGivesIsKept() {
        CreateMountTargetRequest request = request("subnet-1", IpAddressType.IPV4_ONLY);
        request.setIpAddress("10.0.5.5");

        assertEquals("10.0.5.5", service.createMountTarget(request, REGION).getIpAddress());
    }

    @Test
    void mountTargetWithoutASubnetIsRejectedAndNothingIsStored() {
        for (String subnet : new String[] {null, " "}) {
            EfsException e = assertThrows(EfsException.class,
                    () -> service.createMountTarget(request(subnet, null), REGION));
            assertEquals("BadRequest", e.getErrorCode());
        }

        // A stored mount target with no subnet would break the duplicate scan of the next create.
        service.createMountTarget(request("subnet-1", null), REGION);
        assertThrows(AwsException.class, () -> service.createMountTarget(request("subnet-1", null), REGION));
    }
}
