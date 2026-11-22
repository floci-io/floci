package io.floci.aws.core.common.docker;

import io.floci.aws.core.common.AwsException;

import java.util.Map;

/**
 * A container a service asked for would take it past its {@code floci.docker.service-max-containers}
 * budget. It is the {@link AwsException} the service's own API declares for running out of capacity or
 * hitting a limit, so the error reaches the caller in the shape its SDK knows (and, for the throttling
 * codes, backs off from), not as a transport failure or an internal error.
 */
public final class DockerCapacityExceededException extends AwsException {

    /** Error code and HTTP status per {@code io.floci.service} label, from each service's botocore model. */
    private static final Map<String, Error> ERRORS = Map.ofEntries(
            Map.entry("lambda", new Error("TooManyRequestsException", 429)),
            Map.entry("msk", new Error("TooManyRequestsException", 429)),
            Map.entry("opensearch", new Error("LimitExceededException", 409)),
            Map.entry("codebuild", new Error("AccountLimitExceededException", 400)),
            Map.entry("sagemaker", new Error("ResourceLimitExceeded", 400)),
            Map.entry("glue", new Error("ConcurrentRunsExceededException", 400)),
            Map.entry("rds", new Error("InsufficientDBInstanceCapacity", 400)),
            Map.entry("docdb", new Error("DBClusterQuotaExceededFault", 403)),
            Map.entry("neptune", new Error("DBClusterQuotaExceededFault", 403)),
            Map.entry("elasticache", new Error("InsufficientCacheClusterCapacity", 400)),
            Map.entry("redshift", new Error("InsufficientClusterCapacity", 400)),
            Map.entry("memorydb", new Error("InsufficientClusterCapacityFault", 400)));

    /** Services without a modelled capacity error, such as ECS, Batch and EMR, get a generic throttle. */
    private static final Error FALLBACK = new Error("ThrottlingException", 400);

    private final String service;

    public DockerCapacityExceededException(String service, int limit) {
        super(errorFor(service).code(), "Floci's Docker budget for " + service + " is " + limit
                + " running containers (floci.docker.service-max-containers." + service
                + "), and every one is in use", errorFor(service).status());
        this.service = service;
    }

    public String service() {
        return service;
    }

    /** The refusal {@code failure} is or carries as a cause, or null; a failed launch can wrap it. */
    public static DockerCapacityExceededException findIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof DockerCapacityExceededException exceeded) {
                return exceeded;
            }
        }
        return null;
    }

    private static Error errorFor(String service) {
        return ERRORS.getOrDefault(service, FALLBACK);
    }

    private record Error(String code, int status) {
    }
}
