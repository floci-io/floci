package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.ServiceLastAccessedEntity;
import io.github.hectorvent.floci.services.iam.model.ServiceLastAccessedJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Access Advisor job registry behind {@code GenerateServiceLastAccessedDetails} and the two
 * {@code GetServiceLastAccessedDetails*} readers.
 *
 * <p>Floci records no service-access history: nothing populates {@code IamUser.passwordLastUsed},
 * and {@code GetAccessKeyLastUsed} already answers with AWS's documented "never used" shape rather
 * than inventing usage. A job therefore has no report to compute and is complete the moment it is
 * created; the service list it is read back with is derived from the entity's policies by
 * {@code IamQueryHandler}, not stored here.
 *
 * <p>Jobs are never evicted. AWS documents no expiry for a {@code JobId}, so ageing them out would
 * mean inventing a retention window and failing a caller holding an ID that AWS would still
 * answer, which is a worse trade for an emulator than an unbounded store of small records.
 */
@ApplicationScoped
public class ServiceLastAccessedService {

    /** {@code AccessAdvisorUsageGranularityType}; AWS defaults to service-level. */
    private static final Set<String> GRANULARITIES = Set.of("SERVICE_LEVEL", "ACTION_LEVEL");
    private static final String DEFAULT_GRANULARITY = "SERVICE_LEVEL";

    private final StorageBackend<String, ServiceLastAccessedJob> jobs;

    @Inject
    public ServiceLastAccessedService(StorageFactory storageFactory) {
        this(storageFactory.create("iam", "iam-service-last-accessed-jobs.json", new TypeReference<>() {}));
    }

    ServiceLastAccessedService(StorageBackend<String, ServiceLastAccessedJob> jobs) {
        this.jobs = jobs;
    }

    /**
     * Records a completed job for {@code arn} together with the report it produced, and returns
     * it. The report is computed by the caller and stored here, because AWS fixes a report at
     * generation time and the {@code Get*} operations only retrieve it. There is no work left to
     * defer, so the creation and completion timestamps are the same instant.
     */
    public ServiceLastAccessedJob generate(String accountId, String arn, String granularity,
                                           List<String> serviceNamespaces,
                                           List<ServiceLastAccessedEntity> entities) {
        if (granularity != null && !GRANULARITIES.contains(granularity)) {
            throw new AwsException("ValidationError",
                    "Value '" + granularity + "' at 'granularity' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [SERVICE_LEVEL, ACTION_LEVEL]", 400);
        }
        Instant now = Instant.now();
        // jobIDType is min 36 / max 36, which is exactly a UUID string.
        ServiceLastAccessedJob job = new ServiceLastAccessedJob(UUID.randomUUID().toString(), arn,
                granularity == null ? DEFAULT_GRANULARITY : granularity, now, now,
                serviceNamespaces, entities);
        putForAccount(accountId, job);
        return job;
    }

    public ServiceLastAccessedJob get(String accountId, String jobId) {
        return find(accountId, jobId).orElseThrow(() -> new AwsException("NoSuchEntity",
                "The job ID " + jobId + " cannot be found.", 404));
    }

    public Optional<ServiceLastAccessedJob> find(String accountId, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        if (jobs instanceof AccountAwareStorageBackend<ServiceLastAccessedJob> aware) {
            return aware.getForAccount(accountId, jobId);
        }
        return jobs.get(jobId);
    }

    private void putForAccount(String accountId, ServiceLastAccessedJob job) {
        if (jobs instanceof AccountAwareStorageBackend<ServiceLastAccessedJob> aware) {
            aware.putForAccount(accountId, job.getJobId(), job);
        } else {
            jobs.put(job.getJobId(), job);
        }
    }
}
