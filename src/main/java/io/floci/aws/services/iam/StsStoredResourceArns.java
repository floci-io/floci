package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks of STS for the {@code sts} namespace: whether the role a request names in another account is one STS
 * assumes there. STS holds no resource of its own; the roles it assumes are IAM's.
 */
@ApplicationScoped
public class StsStoredResourceArns implements StoredResourceArns {

    private static final String NAMESPACE = "sts";
    private static final String ROLE = "role/";

    private final IamService iam;

    @Inject
    public StsStoredResourceArns(IamService iam) {
        this.iam = iam;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    /** A request names its role, federated user or root user outright: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * Whether a role ARN naming another account than the caller's names a role IAM holds in that account, which STS
     * assumes there, as its handler finds it: in the ARN's account, by the last segment of the ARN's resource. Such a
     * request is checked on that ARN alone, not also on the caller's role of that name. False when that account holds
     * no such role.
     */
    @Override
    public boolean reachedInItsAccount(String arn, String accountId) {
        if (!AwsArnUtils.isArnFor(arn, "iam")) {
            return false;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (parsed.accountId().isEmpty() || parsed.accountId().equals(accountId) || !resource.startsWith(ROLE)) {
            return false;
        }
        String[] path = resource.split("/", -1);
        return iam.findRole(parsed.accountId(), path[path.length - 1]).isPresent();
    }
}
