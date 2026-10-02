package io.github.hectorvent.floci.services.iam;

/**
 * Who a request comes from, as a resource policy's {@code Principal} element sees it. Each kind is
 * matched only by the principal types that can name it (IAM User Guide, "AWS JSON policy elements:
 * Principal"): an IAM identity by {@code AWS}, a service by {@code Service}, and anyone by
 * {@code "*"}.
 *
 * @param type             the kind of caller
 * @param arn              an IAM caller's own ARN: the user's, or a role session's assumed-role
 *                         session ARN; null for the other kinds
 * @param roleArn          for a role session, the ARN of the role that was assumed, path included,
 *                         which a {@code Principal} naming the role identifies; null otherwise
 * @param servicePrincipal a service caller's principal name, such as {@code sns.amazonaws.com};
 *                         null for the other kinds
 */
public record RequestPrincipal(Type type, String arn, String roleArn, String servicePrincipal) {

    public enum Type { IAM, SERVICE, ANONYMOUS }

    /** An IAM user, account root or federated user, or a role session without its role's ARN. */
    public static RequestPrincipal iam(String arn) {
        return new RequestPrincipal(Type.IAM, arn, null, null);
    }

    /** A role session, matched by its session ARN and by the ARN of the role that was assumed. */
    public static RequestPrincipal roleSession(String sessionArn, String roleArn) {
        return new RequestPrincipal(Type.IAM, sessionArn, roleArn, null);
    }

    /**
     * The caller an access key resolved to: a role session when its caller ARN and principal ARN
     * differ, otherwise the IAM identity itself, and anonymous when nothing was resolved. A role
     * session carries the ARN of the role it assumed, path included, because the session ARN alone
     * names the role without its path.
     */
    public static RequestPrincipal caller(IamService.CallerArns callerArns) {
        if (callerArns == null) {
            return anonymous();
        }
        return callerArns.callerArn().equals(callerArns.principalArn())
                ? iam(callerArns.callerArn())
                : roleSession(callerArns.callerArn(), callerArns.principalArn());
    }

    /** An AWS service acting on its own behalf, such as SNS delivering to a queue. */
    public static RequestPrincipal service(String servicePrincipal) {
        return new RequestPrincipal(Type.SERVICE, null, null, servicePrincipal);
    }

    /** A caller with no credentials. */
    public static RequestPrincipal anonymous() {
        return new RequestPrincipal(Type.ANONYMOUS, null, null, null);
    }
}
