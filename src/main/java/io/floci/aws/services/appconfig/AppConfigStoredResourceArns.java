package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks AppConfig of the resources a request names: the application an id or a name finds, the configuration a
 * session's token is on, and the tags of the application an ARN names.
 */
@ApplicationScoped
public class AppConfigStoredResourceArns implements StoredResourceArns {

    private final AppConfigService appConfig;
    private final AppConfigDataService appConfigData;

    @Inject
    public AppConfigStoredResourceArns(AppConfigService appConfig, AppConfigDataService appConfigData) {
        this.appConfig = appConfig;
        this.appConfigData = appConfigData;
    }

    @Override
    public String namespace() {
        return "appconfig";
    }

    /** The application a request names by its id or its name, as AppConfig Data finds the one a session names. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return "application".equals(type) && names.size() == 1
                ? appConfig.applicationArn(names.getFirst(), region) : Optional.empty();
    }

    /**
     * The caller's application, environment, configuration profile or configuration an ARN names by names or ids,
     * as AppConfig's handlers resolve a name, so IAM checks the resource by its ids.
     */
    @Override
    public Optional<String> resolveStoredArn(String arn) {
        return appConfig.storedArn(arn);
    }

    /** The configuration the caller's session of a {@code ConfigurationToken} is on. */
    @Override
    public List<String> through(String member, String value, String region) {
        return "ConfigurationToken".equals(member)
                ? appConfigData.configurationArn(value, region).stream().toList() : List.of();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return StoredResourceArns.withEmptyValues(appConfig.storedTags(arn));
    }
}
