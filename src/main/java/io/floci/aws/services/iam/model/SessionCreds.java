package io.floci.aws.services.iam.model;

/** Temporary AWS credentials injected into an emulated workload. */
public record SessionCreds(String accessKeyId, String secretAccessKey, String sessionToken) {
}
