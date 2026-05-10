package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class StepFunctionsTagHandler implements TagHandler {

    private final StepFunctionsService service;

    @Inject
    public StepFunctionsTagHandler(StepFunctionsService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "states";
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
