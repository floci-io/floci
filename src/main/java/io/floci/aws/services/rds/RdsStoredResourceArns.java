package io.floci.aws.services.rds;

import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.docdb.DocDbService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The RDS ARN namespace is shared by RDS and DocumentDB. */
@ApplicationScoped
public class RdsStoredResourceArns implements StoredResourceArns {
    private final RdsService rds;
    private final DocDbService documentDb;

    @Inject
    public RdsStoredResourceArns(RdsService rds, DocDbService documentDb) {
        this.rds = rds;
        this.documentDb = documentDb;
    }

    @Override
    public String namespace() {
        return "rds";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        // Match Query dispatch precedence, including an owner's empty tag set after untagging.
        return documentDb.storedResourceTags(arn)
                .orElseGet(() -> rds.storedTags(arn));
    }
}
