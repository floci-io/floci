package io.floci.aws.services.ecr;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks ECR of the resources a request names: the tags of the repository an ARN names, and whether one of
 * another account is acted on there.
 */
@ApplicationScoped
public class EcrStoredResourceArns implements StoredResourceArns {

    private final EcrService ecr;

    @Inject
    public EcrStoredResourceArns(EcrService ecr) {
        this.ecr = ecr;
    }

    @Override
    public String namespace() {
        return "ecr";
    }

    /** ECR mints no id into an ARN a request names by its name, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return ecr.storedTags(arn);
    }

    /** A repository of another account in the request's Region, which ECR acts on in that account's registry. */
    @Override
    public boolean reachedInItsAccount(String arn, String accountId) {
        return ecr.actsInItsAccount(arn, accountId);
    }
}
