package io.floci.aws.services.efs;

import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.efs.model.AccessPointDescription;
import io.floci.aws.services.efs.model.FileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks EFS of the resources a request names: the file system a mount target ({@code MountTargetId}) is in,
 * and the file system or access point an id ({@code ResourceId}) names, which a request checks as theirs.
 */
@ApplicationScoped
public class EfsStoredResourceArns implements StoredResourceArns {

    private final EfsService efs;

    @Inject
    public EfsStoredResourceArns(EfsService efs) {
        this.efs = efs;
    }

    @Override
    public String namespace() {
        return "elasticfilesystem";
    }

    /** EFS mints the id that ends each of its ARNs, so there is no name to look up. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The file system the caller's mount target of the id is in ({@code MountTargetId}), and the access point or file
     * system of the id ({@code ResourceId}), as EFS stores them; none for an id EFS holds none of in the Region, which
     * its handler refuses.
     */
    @Override
    public List<String> through(String member, String value, String region) {
        Optional<String> arn = switch (member) {
            case "MountTargetId" -> efs.findMountTarget(region, value)
                    .flatMap(target -> efs.findFileSystem(region, target.getFileSystemId()))
                    .map(FileSystem::getFileSystemArn);
            case "ResourceId" -> efs.findAccessPoint(region, value)
                    .map(AccessPointDescription::getAccessPointArn)
                    .or(() -> efs.findFileSystem(region, value).map(FileSystem::getFileSystemArn));
            default -> Optional.empty();
        };
        return arn.filter(stored -> !stored.isBlank()).stream().toList();
    }
}
