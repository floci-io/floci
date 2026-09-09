package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ApiKeyAuthValidator {

    private final AppSyncService appSyncService;

    @Inject
    public ApiKeyAuthValidator(AppSyncService appSyncService) {
        this.appSyncService = appSyncService;
    }

    public ApiKey validate(String apiId, String keyValue) {
        return appSyncService.validateApiKey(apiId, keyValue)
                .orElseThrow(AppSyncAuth::unauthorized);
    }
}
