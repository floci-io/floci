package io.floci.aws.services.rds;

import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.docdb.DocDbService;
import io.floci.aws.services.rds.export.RdsExportTaskService;
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
    private final RdsExportTaskService exports;

    @Inject
    public RdsStoredResourceArns(RdsService rds, DocDbService documentDb, RdsExportTaskService exports) {
        this.rds = rds;
        this.documentDb = documentDb;
        this.exports = exports;
    }

    @Override
    public String namespace() {
        return "rds";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if ("snapshot".equals(type) && names.size() == 1) {
            return documentDb.findStoredSnapshotArn(names.getFirst(), region)
                    .or(() -> rds.findStoredArn(type, names, region));
        }
        return rds.findStoredArn(type, names, region);
    }

    @Override
    public List<String> through(String member, String value, String region) {
        return "ExportTaskIdentifier".equals(member)
                ? exports.storedSourceArn(value, region).stream().toList() : List.of();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        // Match Query dispatch precedence, including an owner's empty tag set after untagging.
        return documentDb.storedResourceTags(arn)
                .orElseGet(() -> rds.storedTags(arn));
    }
}
