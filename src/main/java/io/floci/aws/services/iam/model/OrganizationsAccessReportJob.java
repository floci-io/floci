package io.floci.aws.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One Organizations access report created by {@code GenerateOrganizationsAccessReport}, together
 * with the report it produced.
 *
 * <p>As with {@link ServiceLastAccessedJob}, AWS fixes a report at generation time and
 * {@code GetOrganizationsAccessReport} only retrieves it, so the service list is captured here
 * rather than recomputed on read: attaching an SCP afterwards does not change what an already
 * completed job returns.
 *
 * <p>A job carries its own status because this report has a failure the other one does not. The
 * generator models no error for an entity path that is well-formed but resolves to nothing, while
 * the reader publishes both a {@code FAILED} status and {@code ErrorDetails}, so that is where a
 * path naming an entity outside the organization lands. Which fields carry it is read off the
 * shape of the API rather than off a sentence, so it is an inference, and the alternatives are
 * worse: another service's error code on an IAM operation, or a completed report that cannot be
 * told apart from an organization reaching no services.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrganizationsAccessReportJob {

    private String jobId;
    private String entityPath;
    private String organizationsPolicyId;
    private String jobStatus;
    private Instant jobCreationDate;
    private Instant jobCompletionDate;
    private List<String> serviceNamespaces = new ArrayList<>();
    private String errorCode;
    private String errorMessage;

    public OrganizationsAccessReportJob() {}

    public OrganizationsAccessReportJob(String jobId, String entityPath,
                                        String organizationsPolicyId, String jobStatus,
                                        Instant jobCreationDate, Instant jobCompletionDate,
                                        List<String> serviceNamespaces) {
        this.jobId = jobId;
        this.entityPath = entityPath;
        this.organizationsPolicyId = organizationsPolicyId;
        this.jobStatus = jobStatus;
        this.jobCreationDate = jobCreationDate;
        this.jobCompletionDate = jobCompletionDate;
        setServiceNamespaces(serviceNamespaces);
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getEntityPath() { return entityPath; }
    public void setEntityPath(String entityPath) { this.entityPath = entityPath; }

    public String getOrganizationsPolicyId() { return organizationsPolicyId; }

    public void setOrganizationsPolicyId(String organizationsPolicyId) {
        this.organizationsPolicyId = organizationsPolicyId;
    }

    public String getJobStatus() { return jobStatus; }
    public void setJobStatus(String jobStatus) { this.jobStatus = jobStatus; }

    public Instant getJobCreationDate() { return jobCreationDate; }
    public void setJobCreationDate(Instant jobCreationDate) { this.jobCreationDate = jobCreationDate; }

    public Instant getJobCompletionDate() { return jobCompletionDate; }

    public void setJobCompletionDate(Instant jobCompletionDate) {
        this.jobCompletionDate = jobCompletionDate;
    }

    public List<String> getServiceNamespaces() { return serviceNamespaces; }

    public void setServiceNamespaces(List<String> serviceNamespaces) {
        this.serviceNamespaces = serviceNamespaces == null
                ? new ArrayList<>() : new ArrayList<>(serviceNamespaces);
    }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
