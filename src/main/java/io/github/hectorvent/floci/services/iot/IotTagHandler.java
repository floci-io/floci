package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IotTagHandler implements TagHandler {

    private final IotService iotService;
    private final IotDomainConfigurationService domainConfigurationService;
    private final IotAuthorizerService authorizerService;

    @Inject
    public IotTagHandler(IotService iotService, IotDomainConfigurationService domainConfigurationService,
                         IotAuthorizerService authorizerService) {
        this.iotService = iotService;
        this.domainConfigurationService = domainConfigurationService;
        this.authorizerService = authorizerService;
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
        if (isAuthorizer(arn)) {
            return authorizerService.listTagsForResource(arn);
        }
        return isDomainConfiguration(arn)
                ? domainConfigurationService.listTagsForResource(arn)
                : iotService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isAuthorizer(arn)) {
            authorizerService.tagResource(arn, tags);
        } else if (isDomainConfiguration(arn)) {
            domainConfigurationService.tagResource(arn, tags);
        } else {
            iotService.tagResource(arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isAuthorizer(arn)) {
            authorizerService.untagResource(arn, tagKeys);
        } else if (isDomainConfiguration(arn)) {
            domainConfigurationService.untagResource(arn, tagKeys);
        } else {
            iotService.untagResource(arn, tagKeys);
        }
    }

    /** Domain configurations and authorizers have their own services; every other IoT resource is tagged through IotService. */
    private static boolean isDomainConfiguration(String arn) {
        return arn != null && arn.contains(":domainconfiguration/");
    }

    private static boolean isAuthorizer(String arn) {
        return AwsArnUtils.isArn(arn) && AwsArnUtils.parse(arn).resource().startsWith("authorizer/");
    }
}
