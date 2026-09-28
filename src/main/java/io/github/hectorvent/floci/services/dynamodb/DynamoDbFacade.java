package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbItemAccess;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbTableAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

/**
 * The injection boundary for Java consumers of DynamoDB: CloudFormation, IoT, Step Functions
 * optimized integrations, the IAM condition context, the Redshift zero-ETL backfill and Resource
 * Explorer. It hands out the selected backend capabilities and never touches native storage itself.
 */
@ApplicationScoped
public class DynamoDbFacade implements ResourceProvider {

    private final DynamoDbItemAccess items;
    private final DynamoDbTableAccess tables;
    private final RegionResolver regionResolver;

    @Inject
    public DynamoDbFacade(DynamoDbItemAccess items, DynamoDbTableAccess tables, RegionResolver regionResolver) {
        this.items = items;
        this.tables = tables;
        this.regionResolver = regionResolver;
    }

    /** The ambient request account in {@code region}. */
    public Scope scope(String region) {
        return new Scope(regionResolver.getAccountId(), region);
    }

    public DynamoDbItemAccess items() {
        return items;
    }

    public DynamoDbTableAccess tables() {
        return tables;
    }

    @Override
    public List<ExplorerResource> getResources() {
        return tables.resources(regionResolver.getAccountId());
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("dynamodb:table", "dynamodb", true));
    }
}
