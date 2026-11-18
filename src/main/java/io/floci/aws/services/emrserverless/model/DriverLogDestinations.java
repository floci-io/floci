package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Where one attempt of a run sends its driver's logs, as the attempt was admitted: an S3 folder, and a CloudWatch Logs
 * group with the driver's two streams, each null where it sends none.
 */
@RegisterForReflection
public record DriverLogDestinations(String s3Folder, String logGroup, String stdoutStream, String stderrStream) {
}
