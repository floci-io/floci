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
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition.QueryParameter;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinitionToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudWatchLogsQueryDefinitionService implements Resettable {

    private static final Set<String> LANGUAGES = Set.of("CWLI", "SQL", "PPL");
    private static final Pattern LOG_GROUP_NAME = Pattern.compile("[._/#A-Za-z0-9-]+");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("\\S{36,128}");

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
        requireLength(request.name(), "name", 1, 255);
        requireLength(request.queryString(), "queryString", 1, 10_000);
        optionalLength(request.queryDefinitionId(), "queryDefinitionId", 1, 256);
        String language = request.queryLanguage() == null ? "CWLI" : request.queryLanguage();
        requireLanguage(language);
        for (String group : request.logGroupNames()) {
            requireLength(group, "logGroupNames member", 1, 512);
            if (!LOG_GROUP_NAME.matcher(group).matches()) {
                throw invalid("Invalid logGroupNames member.");
            }
        }
        if (request.parameters().size() > 20) {
            throw invalid("parameters must contain at most 20 members.");
        }
        if (!request.parameters().isEmpty() && !"CWLI".equals(language)) {
            throw invalid("Query parameters are supported only for CWLI queries.");
        }
        for (QueryParameter parameter : request.parameters()) {
            requireLength(parameter.name(), "parameter name", 1, 128);
            if (!PARAMETER_NAME.matcher(parameter.name()).matches()) {
                throw invalid("Invalid parameter name.");
            }
            optionalLength(parameter.defaultValue(), "defaultValue", 0, 1024);
            optionalLength(parameter.description(), "description", 0, 512);
        }
        if (request.clientToken() != null) {
            if (!CLIENT_TOKEN.matcher(request.clientToken()).matches()) {
                throw invalid("clientToken must contain 36 to 128 non-whitespace characters.");
            }
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
        optionalLength(namePrefix, "queryDefinitionNamePrefix", 1, 255);
        if (queryLanguage != null) {
            requireLanguage(queryLanguage);
        }
        int limit = maxResults == null ? 1000 : maxResults;
        if (limit < 1 || limit > 1000) {
            throw invalid("maxResults must be between 1 and 1000.");
        }
        if (nextToken != null && nextToken.isEmpty()) {
            throw invalid("Invalid nextToken.");
        }
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
        requireLength(id, "queryDefinitionId", 1, 256);
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

    private static void requireLanguage(String language) {
        if (!LANGUAGES.contains(language)) {
            throw invalid("queryLanguage must be CWLI, SQL or PPL.");
        }
    }

    private static void optionalLength(String value, String field, int min, int max) {
        if (value != null) {
            requireLength(value, field, min, max);
        }
    }

    private static void requireLength(String value, String field, int min, int max) {
        int length = value == null ? -1 : value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw invalid(field + " must contain " + min + " to " + max + " characters.");
        }
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
