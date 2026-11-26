package io.floci.aws.services.efs;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.efs.model.AccessPointDescription;
import io.floci.aws.services.efs.model.CreateAccessPointRequest;
import io.floci.aws.services.efs.model.CreateFileSystemRequest;
import io.floci.aws.services.efs.model.CreateMountTargetRequest;
import io.floci.aws.services.efs.model.FileSystem;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What EFS gives the request-resources table through a mount target or a resource id: the ARN IAM checks. */
class EfsStoredResourceArnsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private EfsService service;
    private EfsStoredResourceArns stored;
    private FileSystem fileSystem;

    @BeforeEach
    void setUp() {
        service = new EfsService(new InMemoryStorageFactory(ACCOUNT), new RegionResolver(REGION, ACCOUNT));
        stored = new EfsStoredResourceArns(service);
        fileSystem = service.createFileSystem(new CreateFileSystemRequest(), REGION);
    }

    @Test
    void aMountTargetReachesTheFileSystemItIsIn() {
        CreateMountTargetRequest request = new CreateMountTargetRequest();
        request.setFileSystemId(fileSystem.getFileSystemId());
        request.setSubnetId("subnet-1");
        String mountTarget = service.createMountTarget(request, REGION).getMountTargetId();

        assertEquals(List.of(fileSystem.getFileSystemArn()), stored.through("MountTargetId", mountTarget, REGION));
        assertEquals(List.of(), stored.through("MountTargetId", "fsmt-missing", REGION));
        assertEquals(List.of(), stored.through("MountTargetId", mountTarget, "eu-west-1"));
    }

    @Test
    void aResourceIdNamesTheFileSystemOrTheAccessPointOfThatId() {
        CreateAccessPointRequest request = new CreateAccessPointRequest();
        request.setFileSystemId(fileSystem.getFileSystemId());
        request.setClientToken("token-1");
        AccessPointDescription accessPoint = service.createAccessPoint(REGION, request);

        assertEquals(List.of(fileSystem.getFileSystemArn()),
                stored.through("ResourceId", fileSystem.getFileSystemId(), REGION));
        assertEquals(List.of(accessPoint.getAccessPointArn()),
                stored.through("ResourceId", accessPoint.getAccessPointId(), REGION));
        assertEquals(List.of(), stored.through("ResourceId", "fs-missing", REGION));
        assertEquals(List.of(), stored.through("FileSystemId", fileSystem.getFileSystemId(), REGION));
    }
}
