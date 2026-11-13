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
    void mountTargetWithoutAnAddressTypeHasNoIpv6Address() {
        MountTarget created = service.createMountTarget(request("subnet-1", null), REGION);

        assertEquals("10.0.0.10", created.getIpAddress());
        assertNull(created.getIpv6Address());
    }

    @Test
    void ipv4OnlyMountTargetHasNoIpv6Address() {
        MountTarget created = service.createMountTarget(request("subnet-1", IpAddressType.IPV4_ONLY), REGION);

        assertEquals("10.0.0.10", created.getIpAddress());
        assertNull(created.getIpv6Address());
    }

    @Test
    void dualStackMountTargetGetsBothDefaultAddresses() {
        MountTarget created = service.createMountTarget(request("subnet-1", IpAddressType.DUAL_STACK), REGION);

        assertEquals("10.0.0.10", created.getIpAddress());
        assertEquals("2001:db8::10", created.getIpv6Address());
    }

    @Test
    void ipv6OnlyMountTargetGetsAnIpv6AddressAndNoIpv4Address() {
        MountTarget created = service.createMountTarget(request("subnet-1", IpAddressType.IPV6_ONLY), REGION);

        assertNull(created.getIpAddress());
        assertEquals("2001:db8::10", created.getIpv6Address());
        assertEquals(IpAddressType.IPV6_ONLY, created.getIpAddressType());
    }

    @Test
    void theAddressesTheCallerGivesAreKept() {
        CreateMountTargetRequest ipv4 = request("subnet-1", IpAddressType.IPV4_ONLY);
        ipv4.setIpAddress("10.0.5.5");
        assertEquals("10.0.5.5", service.createMountTarget(ipv4, REGION).getIpAddress());

        CreateMountTargetRequest dual = request("subnet-2", IpAddressType.DUAL_STACK);
        dual.setIpAddress("10.0.6.6");
        dual.setIpv6Address("2600:1f18::6");
        MountTarget created = service.createMountTarget(dual, REGION);
        assertEquals("10.0.6.6", created.getIpAddress());
        assertEquals("2600:1f18::6", created.getIpv6Address());

        CreateMountTargetRequest ipv6 = request("subnet-3", IpAddressType.IPV6_ONLY);
        ipv6.setIpv6Address("2600:1f18::7");
        assertEquals("2600:1f18::7", service.createMountTarget(ipv6, REGION).getIpv6Address());
    }

    @Test
    void anIpv6AddressOnAnIpv4OnlyOrUntypedMountTargetIsRejectedAndNothingIsStored() {
        for (IpAddressType type : new IpAddressType[] {null, IpAddressType.IPV4_ONLY}) {
            CreateMountTargetRequest request = request("subnet-1", type);
            request.setIpv6Address("2600:1f18::1");

            EfsException e = assertThrows(EfsException.class, () -> service.createMountTarget(request, REGION));

            assertEquals("BadRequest", e.getErrorCode());
            assertEquals("Ipv6Address cannot be specified when IpAddressType is IPV4_ONLY.", e.getMessage());
        }
        assertEquals(0, service.getFileSystem(REGION, fileSystemId).getNumberOfMountTargets());
    }

    @Test
    void anIpv4AddressOnAnIpv6OnlyMountTargetIsRejectedAndNothingIsStored() {
        CreateMountTargetRequest request = request("subnet-1", IpAddressType.IPV6_ONLY);
        request.setIpAddress("10.0.5.5");

        EfsException e = assertThrows(EfsException.class, () -> service.createMountTarget(request, REGION));

        assertEquals("BadRequest", e.getErrorCode());
        assertEquals("IpAddress cannot be specified when IpAddressType is IPV6_ONLY.", e.getMessage());
        assertEquals(0, service.getFileSystem(REGION, fileSystemId).getNumberOfMountTargets());
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
