package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One Access Advisor job created by {@code GenerateServiceLastAccessedDetails}, together with the
 * report it produced.
 *
 * <p>AWS generates a report once and the {@code Get*} operations retrieve that report, so the
 * answer for a completed job is fixed at generation time. The service namespaces and entities are
 * therefore captured here rather than recomputed on read: editing a policy afterwards does not
 * change what an already-completed job returns, and deleting the entity does not make a valid
 * {@code JobId} stop resolving.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceLastAccessedJob {

    private String jobId;
    private String arn;
    private String granularity;
    private Instant jobCreationDate;
    private Instant jobCompletionDate;
    private List<String> serviceNamespaces = new ArrayList<>();
    private List<ServiceLastAccessedEntity> entities = new ArrayList<>();

    public ServiceLastAccessedJob() {}

    public ServiceLastAccessedJob(String jobId, String arn, String granularity,
                                  Instant jobCreationDate, Instant jobCompletionDate,
                                  List<String> serviceNamespaces,
                                  List<ServiceLastAccessedEntity> entities) {
        this.jobId = jobId;
        this.arn = arn;
        this.granularity = granularity;
        this.jobCreationDate = jobCreationDate;
        this.jobCompletionDate = jobCompletionDate;
        setServiceNamespaces(serviceNamespaces);
        setEntities(entities);
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getGranularity() { return granularity; }
    public void setGranularity(String granularity) { this.granularity = granularity; }

    public Instant getJobCreationDate() { return jobCreationDate; }
    public void setJobCreationDate(Instant jobCreationDate) { this.jobCreationDate = jobCreationDate; }

    public Instant getJobCompletionDate() { return jobCompletionDate; }
    public void setJobCompletionDate(Instant jobCompletionDate) { this.jobCompletionDate = jobCompletionDate; }

    public List<String> getServiceNamespaces() { return serviceNamespaces; }

    public void setServiceNamespaces(List<String> serviceNamespaces) {
        this.serviceNamespaces = serviceNamespaces == null ? new ArrayList<>() : new ArrayList<>(serviceNamespaces);
    }

    public List<ServiceLastAccessedEntity> getEntities() { return entities; }

    public void setEntities(List<ServiceLastAccessedEntity> entities) {
        this.entities = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
    }
}
