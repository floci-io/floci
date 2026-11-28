package io.floci.aws.services.configservice;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks AWS Config of the resources a request names: the ARN the caller's config rule, configuration recorder
 * or conformance pack of the name a request gives is stored under, whose last part is the id Config minted for it,
 * which the name alone does not carry.
 */
@ApplicationScoped
public class ConfigStoredResourceArns implements StoredResourceArns {

    private final AwsConfigService config;

    @Inject
    public ConfigStoredResourceArns(AwsConfigService config) {
        this.config = config;
    }

    @Override
    public String namespace() {
        return "config";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (names.size() != 1 || names.getFirst() == null) {
            return Optional.empty();
        }
        return config.storedArn(type, region, names.getFirst()).filter(arn -> !arn.isBlank());
    }
}
