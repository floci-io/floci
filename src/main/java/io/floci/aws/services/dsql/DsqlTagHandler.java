package io.floci.aws.services.dsql;

import io.floci.aws.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Aurora DSQL's {@code /tags/{resourceArn}}: a {@code tags} map, POST to tag, answered 200. */
@ApplicationScoped
public class DsqlTagHandler implements TagHandler {

    private final DsqlService service;

    @Inject
    public DsqlTagHandler(DsqlService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "dsql";
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
        return service.listTags(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(region, arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(region, arn, tagKeys);
    }
}
