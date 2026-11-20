package io.floci.aws.services.fis;

import java.util.Set;

/**
 * One running {@code aws:fis:inject-api-*} action: the calls it impairs, made by which roles, until when.
 *
 * @param kind       the error the action injects
 * @param service    the API namespace whose calls it impairs
 * @param operations the operations of that namespace it impairs
 * @param percentage the share of matching calls, from 1 to 100, that fail
 * @param roleArns   the roles whose calls it impairs, from the action's resolved {@code Roles} target
 * @param endTime    when the action's duration runs out, in epoch seconds
 */
record FisApiFault(Kind kind, String service, Set<String> operations, int percentage, Set<String> roleArns,
                   double endTime) {

    static final String ACTION_PREFIX = "aws:fis:inject-api-";

    /** The namespaces AWS lists for the {@code service} parameter. */
    static final Set<String> AWS_SERVICES = Set.of("ec2", "kinesis");

    static boolean isInjectApiAction(String actionId) {
        return actionId != null && actionId.startsWith(ACTION_PREFIX);
    }

    /**
     * Whether a call to {@code operation}, made with a session of {@code roleArn}, is impaired. {@code namespaces}
     * are the names of the service the call reaches: the scope it is served under and its IAM namespace, which differ
     * for a few services, such as CloudWatch's {@code monitoring} and {@code cloudwatch}.
     */
    boolean matches(String roleArn, Set<String> namespaces, String operation) {
        return roleArns.contains(roleArn) && namespaces.contains(service) && operations.contains(operation);
    }

    /** The error an {@code aws:fis:inject-api-*} action injects, named by the suffix of its action ID. */
    enum Kind {
        INTERNAL("internal-error"),
        THROTTLE("throttle-error"),
        UNAVAILABLE("unavailable-error");

        private final String suffix;

        Kind(String suffix) {
            this.suffix = suffix;
        }

        static Kind of(String actionId) {
            for (Kind kind : values()) {
                if ((ACTION_PREFIX + kind.suffix).equals(actionId)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Not an inject-api action: " + actionId);
        }
    }
}
