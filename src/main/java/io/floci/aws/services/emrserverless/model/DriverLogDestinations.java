package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Where one attempt of a run sends its driver's logs, as the attempt was admitted: an S3 folder, and a CloudWatch Logs
 * group with the driver's two streams, each null where it sends none; and whether EMR Serverless keeps them in its
 * managed storage, which its application UIs need, null for an attempt stored before Floci kept it, which its run's
 * configuration then tells.
 */
@RegisterForReflection
public record DriverLogDestinations(String s3Folder, String logGroup, String stdoutStream, String stderrStream,
                                    Boolean managedStorage) {

    /** Whether the attempt's logs are kept in managed storage. */
    public boolean inManagedStorage() {
        return !Boolean.FALSE.equals(managedStorage);
    }
}
