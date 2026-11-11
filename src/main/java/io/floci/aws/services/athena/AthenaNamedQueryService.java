package io.floci.aws.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.athena.model.NamedQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class AthenaNamedQueryService {

    private final StorageBackend<String, NamedQuery> queries;
    private final StorageBackend<String, NamedQuery> requests;
    private final AthenaService athenaService;
    private final RegionResolver regionResolver;

    @Inject
    public AthenaNamedQueryService(StorageFactory storageFactory, AthenaService athenaService,
                                   RegionResolver regionResolver) {
        this.queries = storageFactory.create("athena", "athena-named-queries.json", new TypeReference<>() {});
        this.requests = storageFactory.create("athena", "athena-named-query-requests.json", new TypeReference<>() {});
        this.athenaService = athenaService;
        this.regionResolver = regionResolver;
    }

    public synchronized String createNamedQuery(String region, String name, String database, String queryString,
                                                 String description, String workGroup, String clientRequestToken) {
        validateText(name, "Name", 1, 128);
        validateText(database, "Database", 1, 255);
        validateText(queryString, "QueryString", 1, 262144);
        if (description != null) {
            validateText(description, "Description", 1, 1024);
        }
        validateText(clientRequestToken, "ClientRequestToken", 32, 128);
        String group = resolveWorkGroup(workGroup);
        NamedQuery previous = requests.get(key(region, clientRequestToken)).orElse(null);
        if (previous != null) {
            if (!previous.name().equals(name) || !previous.database().equals(database)
                    || !previous.queryString().equals(queryString) || !Objects.equals(previous.description(), description)
                    || !previous.workGroup().equals(group)) {
                throw invalid("ClientRequestToken has already been used with different parameters.");
            }
            return previous.namedQueryId();
        }
        athenaService.getWorkGroup(group, region);
        String id = UUID.randomUUID().toString();
        NamedQuery query = new NamedQuery(id, name, database, queryString, description, group);
        queries.put(key(region, id), query);
        requests.put(key(region, clientRequestToken), query);
        return id;
    }

    public NamedQuery getNamedQuery(String region, String id) {
        validateId(id);
        return queries.get(key(region, id))
                .orElseThrow(() -> invalid("Named query " + id + " does not exist."));
    }

    public Map<String, Object> batchGetNamedQuery(String region, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 50) {
            throw invalid("NamedQueryIds must contain between 1 and 50 IDs.");
        }
        ids.forEach(AthenaNamedQueryService::validateId);
        List<NamedQuery> found = new ArrayList<>();
        List<Map<String, String>> unprocessed = new ArrayList<>();
        for (String id : ids) {
            NamedQuery query = queries.get(key(region, id)).orElse(null);
            if (query == null) {
                unprocessed.add(Map.of("NamedQueryId", id, "ErrorCode", "InvalidRequestException",
                        "ErrorMessage", "Named query " + id + " does not exist."));
            } else {
                found.add(query);
            }
        }
        return Map.of("NamedQueries", found, "UnprocessedNamedQueryIds", unprocessed);
    }

    public PaginatedResult<NamedQuery> listNamedQueries(String region, String workGroup,
                                                        Integer maxResults, String nextToken) {
        String group = resolveWorkGroup(workGroup);
        if (maxResults != null && (maxResults < 0 || maxResults > 50)) {
            throw invalid("MaxResults must be between 0 and 50.");
        }
        if (nextToken != null && (nextToken.isEmpty() || nextToken.length() > 1024)) {
            throw invalid("NextToken must contain 1 to 1024 characters.");
        }
        athenaService.getWorkGroup(group, region);
        List<NamedQuery> matching = queries.scan(k -> k.startsWith(region + ":")).stream()
                .filter(query -> group.equals(query.workGroup())).toList();
        int limit = maxResults == null || maxResults == 0 ? 50 : maxResults;
        return Pagination.paginate(matching, NamedQuery::namedQueryId, limit, nextToken,
                "ListNamedQueries:" + regionResolver.getAccountId() + ":" + region + ":" + group,
                token -> invalid("Invalid NextToken."));
    }

    public synchronized void updateNamedQuery(String region, String id, String name,
                                               String queryString, String description) {
        validateText(name, "Name", 1, 128);
        validateText(queryString, "QueryString", 1, 262144);
        if (description != null) {
            validateText(description, "Description", 0, 1024);
        }
        NamedQuery previous = getNamedQuery(region, id);
        queries.put(key(region, id), new NamedQuery(id, name, previous.database(), queryString,
                description == null ? previous.description() : description, previous.workGroup()));
    }

    public synchronized void deleteNamedQuery(String region, String id) {
        validateId(id);
        queries.delete(key(region, id));
    }

    private static String resolveWorkGroup(String workGroup) {
        String group = workGroup == null ? "primary" : workGroup;
        if (!group.matches("[a-zA-Z0-9._-]{1,128}")) {
            throw invalid("Invalid WorkGroup.");
        }
        return group;
    }

    private static void validateId(String id) {
        validateText(id, "NamedQueryId", 1, 128);
        if (!id.matches("\\S+")) {
            throw invalid("Invalid NamedQueryId.");
        }
    }

    private static void validateText(String value, String field, int minimum, int maximum) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw invalid(field + " must contain between " + minimum + " and " + maximum + " characters.");
        }
    }

    private static String key(String region, String value) {
        return region + ":" + value;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
