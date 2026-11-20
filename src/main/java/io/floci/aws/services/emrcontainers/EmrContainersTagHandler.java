package io.floci.aws.services.emrcontainers;

import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * EMR on EKS on the shared {@code /tags/{resourceArn}} path, for virtual clusters and their job runs
 * and managed endpoints: the model's ARN pattern names no other resource. The wire shape is the
 * common one (a lowercase {@code tags} map, a {@code tagKeys} query and {@code POST}), except that
 * TagResource and UntagResource answer 200 rather than 204, and payloads are validated strictly.
 */
@ApplicationScoped
public class EmrContainersTagHandler implements TagHandler {

    private final EmrContainersService service;

    @Inject
    public EmrContainersTagHandler(EmrContainersService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return EmrContainersService.SERVICE;
    }

    /** Tag values must be JSON strings, as the model's TagMap declares; nothing is coerced. */
    @Override
    public boolean strictTagValidation() {
        return true;
    }

    /** The model gives TagResource and UntagResource no other response code, so they answer 200. */
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
        return service.listTags(arn);
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
