package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks of its own resources a request names: what a delegation request records, which is the owner
 * the condition key the Service Reference publishes on that type is evaluated against.
 *
 * <p>The delegation store is reached through {@link DelegationRequestOwnership} rather than directly, as
 * {@code IamService} reaches it, so the lookup does not tie IAM's enforcement to the service that holds
 * the requests.
 */
@ApplicationScoped
public class IamStoredResourceArns implements StoredResourceArns {

    private static final String DELEGATION_REQUEST = "delegation-request/";
    private static final String OWNER = "OwnerId";

    private final Instance<DelegationRequestOwnership> delegationRequests;

    @Inject
    public IamStoredResourceArns(Instance<DelegationRequestOwnership> delegationRequests) {
        this.delegationRequests = delegationRequests;
    }

    @Override
    public String namespace() {
        return "iam";
    }

    /** IAM's own ARNs carry the names a request gives, so there is no stored ARN to find behind one. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only a delegation request records anything a condition key is read from, and only its owner, so an ARN
     * of any other type and any other member are answered with nothing. The id is what resolves a request, and
     * the account the ARN names is the one the owner is answered to.
     */
    @Override
    public Optional<String> recorded(String arn, String member) {
        if (!OWNER.equals(member) || !AwsArnUtils.isArn(arn) || delegationRequests.isUnsatisfied()) {
            return Optional.empty();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith(DELEGATION_REQUEST)) {
            return Optional.empty();
        }
        String id = parsed.resource().substring(DELEGATION_REQUEST.length());
        if (id.isBlank()) {
            return Optional.empty();
        }
        return delegationRequests.get().ownerArnOf(parsed.accountId(), id);
    }
}
