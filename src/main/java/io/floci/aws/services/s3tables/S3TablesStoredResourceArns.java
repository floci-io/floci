package io.floci.aws.services.s3tables;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks S3 Tables of the resources a request names: the table a request names by its table bucket's ARN, its
 * namespace and its name, whose ARN ends in an id the service mints, so only the stored table gives it; and the tags
 * of a table bucket or a table, which a resource-tag condition reads.
 */
@ApplicationScoped
public class S3TablesStoredResourceArns implements StoredResourceArns {

    private final S3TablesService s3TablesService;

    @Inject
    public S3TablesStoredResourceArns(S3TablesService s3TablesService) {
        this.s3TablesService = s3TablesService;
    }

    @Override
    public String namespace() {
        return "s3tables";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"Table".equals(type) || names.size() != 3) {
            return Optional.empty();
        }
        return s3TablesService.tableArn(names.get(0), names.get(1), names.get(2), region);
    }

    /** The tags of the table bucket or the table {@code arn} names, which a resource-tag condition reads. */
    @Override
    public Map<String, String> storedTags(String arn) {
        return s3TablesService.storedTags(arn);
    }
}
