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
        String group = resolveWorkGroup(workGroup);
        NamedQuery previous = clientRequestToken == null ? null : requests.get(key(region, clientRequestToken)).orElse(null);
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
        if (clientRequestToken != null) {
            requests.put(key(region, clientRequestToken), query);
        }
        return id;
    }

    public NamedQuery getNamedQuery(String region, String id) {
        return queries.get(key(region, id))
                .orElseThrow(() -> invalid("Named query " + id + " does not exist."));
    }

    public Map<String, Object> batchGetNamedQuery(String region, List<String> ids) {
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
        athenaService.getWorkGroup(group, region);
        int limit = maxResults == null || maxResults == 0 ? 50 : maxResults;
        return Pagination.paginate(queriesInWorkGroup(region, group), NamedQuery::namedQueryId, limit, nextToken,
                "ListNamedQueries:" + regionResolver.getAccountId() + ":" + region + ":" + group,
                token -> invalid("Invalid NextToken."));
    }

    public synchronized void updateNamedQuery(String region, String id, String name,
                                               String queryString, String description) {
        NamedQuery previous = getNamedQuery(region, id);
        queries.put(key(region, id), new NamedQuery(id, name, previous.database(), queryString,
                description == null ? previous.description() : description, previous.workGroup()));
    }

    public synchronized void deleteNamedQuery(String region, String id) {
        queries.delete(key(region, id));
    }

    public synchronized void deleteWorkGroup(String region, String workGroup, boolean recursive) {
        if ("primary".equals(workGroup)) {
            throw invalid("The primary workgroup cannot be deleted.");
        }
        List<NamedQuery> contents = queriesInWorkGroup(region, workGroup);
        if (!recursive && !contents.isEmpty()) {
            throw invalid("WorkGroup contains named queries. Set RecursiveDeleteOption to true to delete it.");
        }
        for (NamedQuery query : contents) {
            queries.delete(key(region, query.namedQueryId()));
        }
        for (String requestKey : requests.keys()) {
            if (requestKey.startsWith(region + ":") && requests.get(requestKey)
                    .map(query -> workGroup.equals(query.workGroup())).orElse(false)) {
                requests.delete(requestKey);
            }
        }
        athenaService.removeWorkGroup(workGroup, region);
    }

    private List<NamedQuery> queriesInWorkGroup(String region, String workGroup) {
        return queries.scan(k -> k.startsWith(region + ":")).stream()
                .filter(query -> workGroup.equals(query.workGroup())).toList();
    }

    private static String resolveWorkGroup(String workGroup) {
        return workGroup == null ? "primary" : workGroup;
    }

    private static String key(String region, String value) {
        return region + ":" + value;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
