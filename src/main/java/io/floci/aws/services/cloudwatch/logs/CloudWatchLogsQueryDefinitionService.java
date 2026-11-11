package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SignedMarkers;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinitionToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class CloudWatchLogsQueryDefinitionService implements Resettable {

    private final StorageBackend<String, QueryDefinition> definitions;
    private final StorageBackend<String, QueryDefinitionToken> clientTokens;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final SignedMarkers markers = new SignedMarkers();
    private boolean resetting;

    @Inject
    public CloudWatchLogsQueryDefinitionService(StorageFactory factory, RegionResolver regionResolver) {
        this(factory.create("cloudwatchlogs", "cwlogs-query-definitions.json", new TypeReference<>() {}),
                factory.create("cloudwatchlogs", "cwlogs-query-definition-tokens.json", new TypeReference<>() {}),
                regionResolver, Clock.systemUTC());
    }

    CloudWatchLogsQueryDefinitionService(StorageBackend<String, QueryDefinition> definitions,
                                         StorageBackend<String, QueryDefinitionToken> clientTokens,
                                         RegionResolver regionResolver, Clock clock) {
        this.definitions = definitions;
        this.clientTokens = clientTokens;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    public synchronized String putQueryDefinition(QueryDefinition request, String region) {
        requireWritable();
        String language = request.queryLanguage() == null ? "CWLI" : request.queryLanguage();
        if (!request.parameters().isEmpty() && !"CWLI".equals(language)) {
            throw invalid("Query parameters are supported only for CWLI queries.");
        }
        if (request.clientToken() != null) {
            QueryDefinitionToken saved = clientTokens.get(key(region, request.clientToken())).orElse(null);
            if (saved != null) {
                if (!sameDefinition(request, saved.request(), language)) {
                    throw invalid("clientToken was already used for a different query definition.");
                }
                return saved.queryDefinitionId();
            }
        }
        String id = request.queryDefinitionId();
        if (id == null) {
            id = UUID.randomUUID().toString();
        } else if (definitions.get(key(region, id)).isEmpty()) {
            throw notFound();
        }
        definitions.put(key(region, id), new QueryDefinition(id, request.name(), request.queryString(),
                request.logGroupNames(), language, request.parameters(), clock.millis(), request.clientToken()));
        if (request.clientToken() != null) {
            QueryDefinition original = new QueryDefinition(request.queryDefinitionId(), request.name(),
                    request.queryString(), request.logGroupNames(), language, request.parameters(), 0, request.clientToken());
            clientTokens.put(key(region, request.clientToken()), new QueryDefinitionToken(original, id));
        }
        return id;
    }

    public PaginatedResult<QueryDefinition> describeQueryDefinitions(String namePrefix, String queryLanguage,
                                                                     Integer maxResults, String nextToken,
                                                                     String region) {
        int limit = maxResults == null ? 1000 : maxResults;
        List<QueryDefinition> matches = definitions.scan(key -> key.startsWith(region + "/")).stream()
                .filter(definition -> namePrefix == null || definition.name().startsWith(namePrefix))
                .filter(definition -> queryLanguage == null || queryLanguage.equals(definition.queryLanguage()))
                .toList();
        String scope = "query-definitions:" + regionResolver.getAccountId() + ":" + region + ":"
                + (namePrefix == null ? "-" : namePrefix.length() + ":" + namePrefix) + ":" + queryLanguage;
        return Pagination.paginateExpiring(matches, QueryDefinition::queryDefinitionId, limit, nextToken,
                scope, clock, Duration.ofHours(24), markers, token -> invalid("Invalid nextToken."));
    }

    public synchronized boolean deleteQueryDefinition(String id, String region) {
        requireWritable();
        String key = key(region, id);
        if (definitions.get(key).isEmpty()) {
            throw notFound();
        }
        definitions.delete(key);
        return true;
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public synchronized void clear() {
        definitions.clear();
        clientTokens.clear();
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }

    private void requireWritable() {
        if (resetting) {
            throw new AwsException("ServiceUnavailableException", "The emulator is resetting.", 503);
        }
    }

    private static boolean sameDefinition(QueryDefinition request, QueryDefinition saved, String language) {
        return Objects.equals(request.queryDefinitionId(), saved.queryDefinitionId())
                && Objects.equals(request.name(), saved.name())
                && Objects.equals(request.queryString(), saved.queryString())
                && request.logGroupNames().equals(saved.logGroupNames())
                && language.equals(saved.queryLanguage())
                && request.parameters().equals(saved.parameters());
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException", "The specified query definition does not exist.", 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
