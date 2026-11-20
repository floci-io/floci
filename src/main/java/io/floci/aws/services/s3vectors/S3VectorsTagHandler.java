package io.floci.aws.services.s3vectors;

import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** S3 Vectors uses the shared REST tag path for both buckets and indexes. */
@ApplicationScoped
public class S3VectorsTagHandler implements TagHandler {
    private final S3VectorsService service;

    @Inject
    public S3VectorsTagHandler(S3VectorsService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "s3vectors";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys);
    }
}
