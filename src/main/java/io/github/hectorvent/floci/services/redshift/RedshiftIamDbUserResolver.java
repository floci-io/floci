package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Derives the Redshift Data API DbUser name for GetClusterCredentialsWithIAM from
 * the caller's IAM identity: an IAM user becomes "IAM:<name>", an assumed role
 * becomes "IAMR:<role>". When the caller cannot be resolved (for example a bare
 * test key with no registered identity) the call still succeeds with a fixed
 * fallback so local and CI flows are not blocked.
 */
@ApplicationScoped
public class RedshiftIamDbUserResolver {

    private static final Logger LOG = Logger.getLogger(RedshiftIamDbUserResolver.class);
    private static final String FALLBACK_DB_USER = "IAMR:floci";

    private final IamService iamService;
    private final AccountResolver accountResolver;

    @Inject
    public RedshiftIamDbUserResolver(IamService iamService, AccountResolver accountResolver) {
        this.iamService = iamService;
        this.accountResolver = accountResolver;
    }

    public String resolveDbUser(String authorizationHeader) {
        String accessKeyId = accountResolver.extractAccessKeyId(authorizationHeader);
        Optional<String> arn = iamService.resolveCallerArn(accessKeyId);
        if (arn.isEmpty()) {
            LOG.warnv("GetClusterCredentialsWithIAM caller could not be resolved; using {0}", FALLBACK_DB_USER);
            return FALLBACK_DB_USER;
        }
        String resource = arn.get().substring(arn.get().lastIndexOf(':') + 1);
        if (resource.startsWith("assumed-role/")) {
            String[] parts = resource.split("/");
            return parts.length >= 2 ? "IAMR:" + parts[1] : FALLBACK_DB_USER;
        }
        if (resource.startsWith("user/")) {
            // Drop any IAM path prefix (user/team/alice -> alice), matching how AWS names the DbUser.
            return "IAM:" + resource.substring(resource.lastIndexOf('/') + 1);
        }
        LOG.warnv("GetClusterCredentialsWithIAM caller ARN {0} is not a user or role; using {1}",
                arn.get(), FALLBACK_DB_USER);
        return FALLBACK_DB_USER;
    }
}
