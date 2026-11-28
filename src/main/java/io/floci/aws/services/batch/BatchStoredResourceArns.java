package io.floci.aws.services.batch;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks Batch of the resources a request names: the job definition revision a reference names, by name, name
 * and revision or ARN ({@code find}), and the tags Batch holds on a compute environment, a job queue, a job
 * definition revision or a job, which an {@code aws:ResourceTag} condition reads.
 */
@ApplicationScoped
public class BatchStoredResourceArns implements StoredResourceArns {

    private final BatchService batch;

    @Inject
    public BatchStoredResourceArns(BatchService batch) {
        this.batch = batch;
    }

    @Override
    public String namespace() {
        return "batch";
    }

    /** Batch stores no resource under a name a request fills an ARN with: a job's id is one it mints. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The job definition revision a reference names, as Batch resolves it, a name alone being the latest active revision
     * a submitted job runs; none for a job a request submits, whose id Batch mints, and for any other type.
     */
    @Override
    public Optional<String> findByMembers(String type, List<String> names, String region) {
        return "job-definition-revision".equals(type) && names.size() == 1
                ? batch.jobDefinitionArn(names.getFirst()) : Optional.empty();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return batch.storedTags(arn);
    }
}
