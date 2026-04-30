package io.github.hectorvent.floci.services.ec2;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Resolves EC2 AMI IDs to Docker image URIs.
 *
 * Floci-local AMI IDs (e.g. "ami-amazonlinux2023") map to public Docker images.
 * Real AWS AMI IDs (e.g. "ami-0abc12345678") fall back to amazonlinux:2023.
 */
@ApplicationScoped
public class AmiImageResolver {

    private static final Logger LOG = Logger.getLogger(AmiImageResolver.class);

    private static final String DEFAULT_IMAGE = "public.ecr.aws/amazonlinux/amazonlinux:2023";

    private static final Map<String, String> BUILTIN_MAPPINGS = Map.ofEntries(
            Map.entry("ami-amazonlinux2023", "public.ecr.aws/amazonlinux/amazonlinux:2023"),
            Map.entry("ami-amazonlinux2",    "public.ecr.aws/amazonlinux/amazonlinux:2"),
            Map.entry("ami-ubuntu2204",      "public.ecr.aws/docker/library/ubuntu:22.04"),
            Map.entry("ami-ubuntu2004",      "public.ecr.aws/docker/library/ubuntu:20.04"),
            Map.entry("ami-debian12",        "public.ecr.aws/docker/library/debian:12"),
            Map.entry("ami-alpine",          "public.ecr.aws/docker/library/alpine:latest")
    );

    /**
     * Resolves an AMI ID to a Docker image URI.
     * Falls back to Amazon Linux 2023 for unrecognised IDs.
     */
    public String resolve(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            LOG.warnv("No imageId provided; using default image {0}", DEFAULT_IMAGE);
            return DEFAULT_IMAGE;
        }

        String mapped = BUILTIN_MAPPINGS.get(imageId);
        if (mapped != null) {
            return mapped;
        }

        LOG.warnv("Unknown AMI ID {0}; falling back to default image {1}", imageId, DEFAULT_IMAGE);
        return DEFAULT_IMAGE;
    }

    /**
     * Returns the pre-seeded AMI catalogue entries for DescribeImages.
     */
    public static Map<String, String> builtinMappings() {
        return BUILTIN_MAPPINGS;
    }
}
