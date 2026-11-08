package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;

import java.util.Optional;
import java.util.function.Function;

/**
 * The job registry IAM's report generators keep: Access Advisor's service last accessed jobs and
 * the Organizations access reports.
 *
 * <p>Both hand the caller a job id, store the report the job produced rather than recomputing it,
 * and answer {@code NoSuchEntity} for an id they do not hold, so the reading, the writing and the
 * refusal are the same for both and only what a job carries differs. Jobs are scoped to the
 * account that generated them, so one account's id is never answerable by another.
 */
final class IamJobStore<T> {

    private final StorageBackend<String, T> jobs;
    private final Function<T, String> jobId;

    IamJobStore(StorageBackend<String, T> jobs, Function<T, String> jobId) {
        this.jobs = jobs;
        this.jobId = jobId;
    }

    T get(String accountId, String id) {
        return find(accountId, id).orElseThrow(() -> new AwsException("NoSuchEntity",
                "The job ID " + id + " cannot be found.", 404));
    }

    Optional<T> find(String accountId, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (jobs instanceof AccountAwareStorageBackend<T> aware) {
            return aware.getForAccount(accountId, id);
        }
        return jobs.get(id);
    }

    void put(String accountId, T job) {
        if (jobs instanceof AccountAwareStorageBackend<T> aware) {
            aware.putForAccount(accountId, jobId.apply(job), job);
        } else {
            jobs.put(jobId.apply(job), job);
        }
    }
}
