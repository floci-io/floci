package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * What IAM asks EMR Serverless of the resources a request names: the tags of an application, a job run or a session,
 * which three services hold, so one answer stands for the namespace.
 */
@ApplicationScoped
public class EmrServerlessStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "emr-serverless";

    private final RegionResolver regionResolver;
    private final EmrServerlessService applications;
    private final EmrServerlessJobRunService jobRuns;
    private final EmrServerlessSessionService sessions;

    @Inject
    public EmrServerlessStoredResourceArns(RegionResolver regionResolver, EmrServerlessService applications,
                                           EmrServerlessJobRunService jobRuns, EmrServerlessSessionService sessions) {
        this.regionResolver = regionResolver;
        this.applications = applications;
        this.jobRuns = jobRuns;
        this.sessions = sessions;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names an application, a job run or a session by the id its ARN ends in: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the application, the job run or the session whose ARN is {@code arn}, in the request's account,
     * where the tag handlers read: an ARN of another account names none, and neither does one of a deleted
     * application, nor a job run's or a session's under it. Each is read in place by its id, a record stored before
     * storage was account-aware included, and matched on its application and on the whole ARN it records. Tag calls
     * give the resource a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, NAMESPACE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        Matcher matcher = EmrServerlessService.RESOURCE.matcher(parsed.resource());
        if (!parsed.accountId().equals(account) || !matcher.matches()) {
            return Map.of();
        }
        String applicationId = matcher.group(1);
        Optional<Application> application = applications.storedLive(account, applicationId);
        if (application.isEmpty()) {
            return Map.of();
        }
        Map<String, String> tags;
        if (matcher.group(2) == null) {
            tags = application.filter(app -> arn.equals(app.getArn())).map(Application::getTags).orElse(null);
        } else if ("sessions".equals(matcher.group(2))) {
            tags = sessions.storedSession(account, matcher.group(3))
                    .filter(session -> applicationId.equals(session.getApplicationId()) && arn.equals(session.getArn()))
                    .map(Session::getTags).orElse(null);
        } else {
            tags = jobRuns.storedRun(account, matcher.group(3))
                    .filter(run -> applicationId.equals(run.getApplicationId()) && arn.equals(run.getArn()))
                    .map(JobRun::getTags).orElse(null);
        }
        return tags == null ? Map.of() : new LinkedHashMap<>(tags);
    }
}
