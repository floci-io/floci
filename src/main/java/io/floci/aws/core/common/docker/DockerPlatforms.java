package io.floci.aws.core.common.docker;

import java.util.Locale;

/**
 * The Docker platforms AWS's architecture names stand for, as a service's resources name them: the one mapping
 * ECS task definitions, Lambda functions, EC2 images (and the AMI build tool) and EMR Serverless applications share.
 */
public final class DockerPlatforms {

    private DockerPlatforms() {
    }

    /**
     * The Docker platform an AWS architecture name runs on, {@code x86_64} or {@code arm64} in whatever case a service
     * spells them, or null for none or one Docker has no platform for, which leaves the Docker host's own.
     */
    public static String forArchitecture(String architecture) {
        if (architecture == null) {
            return null;
        }
        return switch (architecture.toLowerCase(Locale.ROOT)) {
            case "x86_64" -> "linux/amd64";
            case "arm64" -> "linux/arm64";
            default -> null;
        };
    }
}
