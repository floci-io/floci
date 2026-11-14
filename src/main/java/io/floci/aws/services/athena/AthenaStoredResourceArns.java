package io.floci.aws.services.athena;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks Athena of the resources a request names: the workgroup and the data catalog its handlers use for a
 * request naming none, {@code primary} and {@code AwsDataCatalog}.
 */
@ApplicationScoped
public class AthenaStoredResourceArns implements StoredResourceArns {

    private final RegionResolver regionResolver;

    @Inject
    public AthenaStoredResourceArns(RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return "athena";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Optional<String> findDefault(String type, String region) {
        return switch (type) {
            case "workgroup" -> Optional.of(regionResolver.buildArn("athena", region, "workgroup/primary"));
            case "datacatalog" -> Optional.of(regionResolver.buildArn("athena", region,
                    "datacatalog/" + AthenaService.DEFAULT_CATALOG));
            default -> Optional.empty();
        };
    }
}
