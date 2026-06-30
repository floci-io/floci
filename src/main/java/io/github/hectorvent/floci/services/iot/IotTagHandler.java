package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IotTagHandler implements TagHandler {

    private final IotService iotService;

    @Inject
    public IotTagHandler(IotService iotService) {
        this.iotService = iotService;
    }

    @Override
    public String serviceKey() {
        return "iot";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return iotService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        iotService.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        iotService.untagResource(arn, tagKeys);
    }
}
