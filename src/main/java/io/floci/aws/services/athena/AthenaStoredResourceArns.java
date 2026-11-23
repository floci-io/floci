package io.floci.aws.services.athena;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks Athena of the resources a request names: the workgroup and the data catalog its handlers use for a
 * request naming none, {@code primary} and {@code AwsDataCatalog}, the workgroup a query execution or a named query of
 * an id belongs to, and the tags of a workgroup or a data catalog.
 */
@ApplicationScoped
public class AthenaStoredResourceArns implements StoredResourceArns {

    private final RegionResolver regionResolver;
    private final AthenaService athena;
    private final AthenaNamedQueryService namedQueries;

    @Inject
    public AthenaStoredResourceArns(RegionResolver regionResolver, AthenaService athena,
                                    AthenaNamedQueryService namedQueries) {
        this.regionResolver = regionResolver;
        this.athena = athena;
        this.namedQueries = namedQueries;
    }

    @Override
    public String namespace() {
        return "athena";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Optional<String> findDefault(String type, String region) {
        return switch (type) {
            case "workgroup" -> Optional.of(regionResolver.buildArn("athena", region, "workgroup/primary"));
            case "datacatalog" -> Optional.of(regionResolver.buildArn("athena", region,
                    "datacatalog/" + AthenaService.DEFAULT_CATALOG));
            default -> Optional.empty();
        };
    }

    /**
     * The workgroup the caller's query execution ({@code QueryExecutionId}) or named query ({@code NamedQueryId}) of the
     * id runs in, {@code primary} for one naming none, as Athena checks a request naming it: an execution's in the
     * Region it was started in, whichever Region the request is sent to, so neither a grant on another Region's
     * workgroup of the same name nor a request sent to another Region passes a check its own workgroup refuses; a named
     * query's in the Region of the request, which holds it. None for an id Athena holds none of, or an execution whose
     * Region it did not record, so a request naming one is checked as naming no workgroup.
     */
    @Override
    public List<String> through(String member, String value, String region) {
        return switch (member) {
            case "QueryExecutionId" -> athena.findQueryExecution(value)
                    .flatMap(execution -> athena.findQueryExecutionRegion(value)
                            .map(startedIn -> workGroupArn(startedIn, execution.getWorkGroup())))
                    .stream().toList();
            case "NamedQueryId" -> namedQueries.findNamedQuery(region, value)
                    .map(query -> workGroupArn(region, query.workGroup()))
                    .stream().toList();
            default -> List.of();
        };
    }

    private String workGroupArn(String region, String group) {
        return regionResolver.buildArn("athena", region,
                "workgroup/" + (group == null || group.isBlank() ? "primary" : group));
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return athena.storedTags(arn);
    }
}
