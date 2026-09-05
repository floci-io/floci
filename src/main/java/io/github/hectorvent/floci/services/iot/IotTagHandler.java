package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IotTagHandler implements TagHandler {

    private final IotService iotService;
    private final IotDomainConfigurationService domainConfigurationService;

    @Inject
    public IotTagHandler(IotService iotService, IotDomainConfigurationService domainConfigurationService) {
        this.iotService = iotService;
        this.domainConfigurationService = domainConfigurationService;
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
        return isDomainConfiguration(arn)
                ? domainConfigurationService.listTagsForResource(arn)
                : iotService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isDomainConfiguration(arn)) {
            domainConfigurationService.tagResource(arn, tags);
        } else {
            iotService.tagResource(arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isDomainConfiguration(arn)) {
            domainConfigurationService.untagResource(arn, tagKeys);
        } else {
            iotService.untagResource(arn, tagKeys);
        }
    }

    /** Domain configurations have their own service; every other IoT resource is tagged through IotService. */
    private static boolean isDomainConfiguration(String arn) {
        return arn != null && arn.contains(":domainconfiguration/");
    }
}
