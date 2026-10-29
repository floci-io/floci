package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.OrganizationsAccessReportJob;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.PolicyTypeSummary;
import io.github.hectorvent.floci.services.organizations.model.Root;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The report registry behind {@code GenerateOrganizationsAccessReport} and
 * {@code GetOrganizationsAccessReport}.
 *
 * <p>The report answers an Organizations question with IAM's policy logic, so the entity path and
 * the SCPs are read from {@link OrganizationsService} through its own public API and the service
 * list is computed by {@link IamPolicyEvaluator#servicesAllowedByScpLevels}. Only SCP logic
 * applies, which the reference is explicit about: identity-based policies, resource policies,
 * permissions boundaries and trust policies are all excluded from this data.
 *
 * <p>Floci records no service access, so every service in a report is one principals are allowed
 * but never used, which is the shape the reference's own example gives such a service: a namespace
 * and a zero count, with no region, entity path or last-authenticated time.
 */
@ApplicationScoped
public class OrganizationsAccessReportService {

    private static final String SERVICE_CONTROL_POLICY = "SERVICE_CONTROL_POLICY";
    private static final String ENABLED = "ENABLED";
    /** What Organizations answers a caller whose account belongs to no organization. */
    private static final String NOT_IN_ORGANIZATION = "AWSOrganizationsNotInUseException";

    /** {@code organizationsEntityPathType}: an organization id, a root id, then the path below it. */
    private static final Pattern ENTITY_PATH =
            Pattern.compile("^o-[0-9a-z]{10,32}\\/r-[0-9a-z]{4,32}[0-9a-z-\\/]*");
    private static final int ENTITY_PATH_MIN = 19;
    private static final int ENTITY_PATH_MAX = 427;
    /** {@code organizationsPolicyIdType}. */
    private static final Pattern POLICY_ID = Pattern.compile("^p-[0-9a-zA-Z_]{8,128}$");

    private final IamJobStore<OrganizationsAccessReportJob> jobs;
    private final OrganizationsService organizationsService;
    private final IamPolicyEvaluator policyEvaluator;
    private final Clock clock;

    @Inject
    public OrganizationsAccessReportService(StorageFactory storageFactory,
                                            OrganizationsService organizationsService,
                                            IamPolicyEvaluator policyEvaluator, Clock clock) {
        this(storageFactory.create("iam", "iam-organizations-access-report-jobs.json",
                new TypeReference<>() {}), organizationsService, policyEvaluator, clock);
    }

    OrganizationsAccessReportService(StorageBackend<String, OrganizationsAccessReportJob> jobs,
                                     OrganizationsService organizationsService,
                                     IamPolicyEvaluator policyEvaluator, Clock clock) {
        this.jobs = new IamJobStore<>(jobs, OrganizationsAccessReportJob::getJobId);
        this.organizationsService = organizationsService;
        this.policyEvaluator = policyEvaluator;
        this.clock = clock;
    }

    /**
     * Generates the report now and stores it with the job, the way the Access Advisor jobs do:
     * AWS fixes a report at generation time and the reader only retrieves it, so there is no work
     * left to defer and the creation and completion timestamps are the same instant.
     *
     * <p>Only the caller's own standing is answered synchronously. The reference models no error
     * on this operation for an entity path that names nothing, and gives the reader a
     * {@code FAILED} status with {@code ErrorDetails} instead, so anything about the state of the
     * organization lands there rather than becoming an error code AWS does not publish.
     */
    public OrganizationsAccessReportJob generate(String callerAccountId, String entityPath,
                                                 String organizationsPolicyId) {
        validateEntityPath(entityPath);
        validatePolicyId(organizationsPolicyId);
        Organization organization = requireManagementAccount(callerAccountId);

        List<String> segments = List.of(entityPath.split("/"));
        if (!segments.get(0).equals(organization.getId())) {
            return failed(callerAccountId, entityPath, organizationsPolicyId, "InvalidEntityPath",
                    "The organization " + segments.get(0) + " is not the caller's organization.");
        }
        Optional<Root> root = findRoot(callerAccountId, segments.get(1));
        if (root.isEmpty()) {
            return failed(callerAccountId, entityPath, organizationsPolicyId, "InvalidEntityPath",
                    "The organization root " + segments.get(1) + " cannot be found.");
        }
        if (!hasServiceControlPoliciesEnabled(root.get())) {
            return failed(callerAccountId, entityPath, organizationsPolicyId,
                    "PolicyTypeNotEnabled",
                    "Service control policies are not enabled for the organization root "
                            + root.get().getId() + ".");
        }
        try {
            requirePathInTree(callerAccountId, entityPath, segments);
            return completed(callerAccountId, entityPath, organizationsPolicyId,
                    servicesFor(organization, segments, organizationsPolicyId));
        } catch (AwsException e) {
            // Organizations answers its own codes for an entity it does not hold, and they are not
            // errors this operation publishes, so the job carries the reason instead.
            return failed(callerAccountId, entityPath, organizationsPolicyId, e.getErrorCode(),
                    e.getMessage());
        }
    }

    public OrganizationsAccessReportJob get(String callerAccountId, String jobId) {
        requireManagementAccount(callerAccountId);
        return jobs.get(callerAccountId, jobId);
    }

    /**
     * The services the SCPs along the path leave reachable. The management account is not limited
     * by SCPs, so its report lists every service and the reference says an
     * {@code OrganizationsPolicyId} passed with it "is ignored". A policy id otherwise narrows the
     * report to that one SCP, and without one the whole chain applies, level by level.
     */
    private List<String> servicesFor(Organization organization, List<String> segments,
                                     String organizationsPolicyId) {
        String callerAccountId = organization.getMasterAccountId();
        if (segments.get(segments.size() - 1).equals(callerAccountId)) {
            return policyEvaluator.servicesAllowedByScpLevels(null);
        }
        if (organizationsPolicyId != null) {
            OrganizationPolicy policy =
                    organizationsService.describePolicy(callerAccountId, organizationsPolicyId);
            if (!SERVICE_CONTROL_POLICY.equals(policy.getType())) {
                // Organizations holds tag, backup, chatbot and opt-out policies under the same id
                // space, and none of them carries statements. Evaluating one as a ceiling would
                // read as a level that constrains nothing and report every service there is.
                throw new AwsException("InvalidInputException", "The policy "
                        + organizationsPolicyId + " is a " + policy.getType()
                        + ", not a service control policy.", 400);
            }
            return policyEvaluator.servicesAllowedByScpLevels(
                    List.of(List.of(policy.getContent() == null ? "" : policy.getContent())));
        }
        List<List<String>> levels = new ArrayList<>();
        for (String node : segments.subList(1, segments.size())) {
            // Each node is resolved by the listing itself: Organizations answers
            // TargetNotFoundException for one it does not hold, which fails the job.
            List<String> documents = new ArrayList<>();
            for (OrganizationPolicy policy : organizationsService
                    .listPoliciesForTarget(callerAccountId, node, SERVICE_CONTROL_POLICY)) {
                if (policy.getContent() != null) {
                    documents.add(policy.getContent());
                }
            }
            levels.add(documents);
        }
        return policyEvaluator.servicesAllowedByScpLevels(levels);
    }

    /**
     * Holds the entity path to a path the organization actually has. The segments below the root
     * name a chain of parent and child, so each one is resolved together with the one above it:
     * Organizations reports the path of any node it holds, and the request has to spell that same
     * path. Resolving the segments one at a time would accept a path assembled from two branches,
     * or one whose intervening OU does not exist at all, and report the policies of a chain the
     * organization does not have.
     */
    private void requirePathInTree(String callerAccountId, String entityPath,
                                   List<String> segments) {
        String node = segments.get(segments.size() - 1);
        // Throws the Organizations code for a node it does not hold, which fails the job.
        String actual = organizationsService.organizationPath(callerAccountId, node);
        if (!trimTrailingSlash(actual).equals(trimTrailingSlash(entityPath))) {
            throw new AwsException("InvalidEntityPath", "The entity path " + entityPath
                    + " is not a path in the organization. " + node + " is at " + actual + ".",
                    400);
        }
    }

    private static String trimTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private Optional<Root> findRoot(String callerAccountId, String rootId) {
        return organizationsService.listRoots(callerAccountId).stream()
                .filter(candidate -> rootId.equals(candidate.getId()))
                .findFirst();
    }

    private boolean hasServiceControlPoliciesEnabled(Root root) {
        for (PolicyTypeSummary policyType : root.getPolicyTypes()) {
            if (SERVICE_CONTROL_POLICY.equals(policyType.getType())
                    && ENABLED.equals(policyType.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Both operations document that the caller must be signed in to the management account.
     * Neither models a code for a caller who is not, but a per-action error list is additive: the
     * reference's common error types apply to every action and carry the 403 permission denial,
     * and the user guide names the code for a denied IAM action in its
     * {@code AddRoleToInstanceProfile} entry. The common error page spells it
     * {@code AccessDeniedException}; the user guide and the rest of this service's surface spell
     * it {@code AccessDenied}, which is what a caller here is already seeing elsewhere.
     */
    private Organization requireManagementAccount(String callerAccountId) {
        Organization organization;
        try {
            organization = organizationsService.describeOrganization(callerAccountId);
        } catch (AwsException e) {
            if (!NOT_IN_ORGANIZATION.equals(e.getErrorCode())) {
                // Only the account-has-no-organization answer means anything about the caller's
                // standing. Anything else is a failure of the read itself, and reporting it as a
                // permission problem would send the caller after the wrong thing.
                throw e;
            }
            // An account in no organization is not a management account either, and
            // AWSOrganizationsNotInUse is an Organizations code this operation does not publish.
            throw managementAccountOnly();
        }
        if (!callerAccountId.equals(organization.getMasterAccountId())) {
            throw managementAccountOnly();
        }
        return organization;
    }

    private static AwsException managementAccountOnly() {
        return new AwsException("AccessDenied",
                "This operation can be performed only by the management account of the "
                        + "organization.", 403);
    }

    /**
     * The constraint AWS names is the one that failed, not whichever one the check happens to end
     * on: a path over the maximum length is a length violation and reads as one, as every other
     * validated IAM member does.
     */
    private static void validateEntityPath(String entityPath) {
        String constraint;
        if (entityPath == null) {
            constraint = "Member must not be null";
        } else if (entityPath.length() < ENTITY_PATH_MIN) {
            constraint = "Member must have length greater than or equal to " + ENTITY_PATH_MIN;
        } else if (entityPath.length() > ENTITY_PATH_MAX) {
            constraint = "Member must have length less than or equal to " + ENTITY_PATH_MAX;
        } else if (!ENTITY_PATH.matcher(entityPath).matches()) {
            constraint = "Member must satisfy regular expression pattern: " + ENTITY_PATH.pattern();
        } else {
            return;
        }
        throw validationError(entityPath, "entityPath", constraint);
    }

    /**
     * {@code OrganizationsPolicyId} is optional, so an absent one is no error. A request that
     * carries the member is held to the pattern whatever it carries, since an empty value is a
     * malformed id rather than a way of leaving the member out.
     */
    private static void validatePolicyId(String organizationsPolicyId) {
        if (organizationsPolicyId != null
                && !POLICY_ID.matcher(organizationsPolicyId).matches()) {
            throw validationError(organizationsPolicyId, "organizationsPolicyId",
                    "Member must satisfy regular expression pattern: " + POLICY_ID.pattern());
        }
    }

    /**
     * IAM's shape-violation message, which names the member and leaves the value out, as
     * {@link IamValidationErrors} reads it. A member the request never sent is the one place a
     * word still stands where the value would, {@code Value null at '<member>'}.
     */
    private static AwsException validationError(String value, String at, String constraint) {
        if (value == null) {
            return SmithyValidationErrors.validationError(SmithyValidationErrors.notNull(at));
        }
        return IamValidationErrors.validationError(at, value, constraint);
    }

    private OrganizationsAccessReportJob completed(String callerAccountId, String entityPath,
                                                   String organizationsPolicyId,
                                                   List<String> serviceNamespaces) {
        Instant now = clock.instant();
        // jobIDType is min 36 / max 36, which is exactly a UUID string.
        OrganizationsAccessReportJob job = new OrganizationsAccessReportJob(
                UUID.randomUUID().toString(), entityPath, organizationsPolicyId, "COMPLETED",
                now, now, serviceNamespaces);
        jobs.put(callerAccountId, job);
        return job;
    }

    private OrganizationsAccessReportJob failed(String callerAccountId, String entityPath,
                                                String organizationsPolicyId, String code,
                                                String message) {
        Instant now = clock.instant();
        OrganizationsAccessReportJob job = new OrganizationsAccessReportJob(
                UUID.randomUUID().toString(), entityPath, organizationsPolicyId, "FAILED",
                now, now, List.of());
        job.setErrorCode(code);
        job.setErrorMessage(message);
        jobs.put(callerAccountId, job);
        return job;
    }
}
