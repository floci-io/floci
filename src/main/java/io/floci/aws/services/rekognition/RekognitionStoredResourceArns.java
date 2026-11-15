package io.floci.aws.services.rekognition;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks Rekognition of the resources a request names: a stream processor by the name it was created with,
 * which its ARN ends in.
 */
@ApplicationScoped
public class RekognitionStoredResourceArns implements StoredResourceArns {

    private final RegionResolver regionResolver;

    @Inject
    public RekognitionStoredResourceArns(RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return "rekognition";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return "streamprocessor".equals(type) && names.size() == 1
                ? Optional.of(regionResolver.buildArn("rekognition", region, "streamprocessor/" + names.getFirst()))
                : Optional.empty();
    }
}
