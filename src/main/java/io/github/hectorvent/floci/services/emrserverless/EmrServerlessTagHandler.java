package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * EMR Serverless on the shared {@code /tags/{resourceArn}} path. The wire shape is the common one (a
 * lowercase {@code tags} map, a {@code tagKeys} query and {@code POST}), except that TagResource and
 * UntagResource answer 200 rather than 204, and payloads are validated strictly.
 */
@ApplicationScoped
public class EmrServerlessTagHandler implements TagHandler {

    private final EmrServerlessService service;

    @Inject
    public EmrServerlessTagHandler(EmrServerlessService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "emr-serverless";
    }

    /** Tag values must be JSON strings, as the model's TagMap declares; nothing is coerced. */
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
