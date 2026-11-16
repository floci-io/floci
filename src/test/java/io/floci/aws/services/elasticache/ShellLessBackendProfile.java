package io.floci.aws.services.elasticache;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs the ElastiCache backend the way an image with no shell and no CLI needs
 * ({@code floci.services.elasticache.shell-less-backend}), against a real image of that kind: the
 * public KiviDB image, which is distroless. The image is pinned by digest, so the tests exercise
 * the same bytes every time; bumping it is a deliberate change to {@link #IMAGE}.
 *
 * <p>KiviDB names its snapshot file {@code dump.kdb} and does not report it as {@code dbfilename},
 * hence the file name override.
 */
public class ShellLessBackendProfile implements QuarkusTestProfile {

    /** KiviDB v1.2.2, the multi-arch index digest. */
    public static final String IMAGE =
            "ghcr.io/kividbio/kividb:v1.2.2@sha256:8853c7b1e73e604502fd584533440cb1205ae311fe805d520543b4e85a4b71e9";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.elasticache.default-image", IMAGE,
                "floci.services.elasticache.shell-less-backend", "true",
                "floci.services.elasticache.shell-less-backend-dbfilename", "dump.kdb");
    }
}
