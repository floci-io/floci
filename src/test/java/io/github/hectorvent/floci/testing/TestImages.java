package io.github.hectorvent.floci.testing;

/**
 * Images the Docker-backed tests start, one constant per image so a tag lives in one place.
 * {@code .github/ci/prefetch-images.sh} reads the values from this file, the way it reads the
 * sidecar pins from {@code application.yml}, so the prefetch cannot drift from the tests.
 * <p>
 * Every image that Docker Hub also carries is named the Docker Hub way. ECR Public meters
 * anonymous pulls by runner IP and by bytes, and GitHub-hosted runners share those IPs, so a job
 * can arrive with the quota already spent or spend it itself: on CI the mirror at
 * {@code public.ecr.aws/docker/library} failed on the first pull of a job, and the Firelens
 * router failed right after the same job had pulled two Lambda runtimes from ECR Public, both
 * with {@code toomanyrequests: Data limit exceeded}. GitHub-hosted runners pull Docker Hub
 * without a limit. The Lambda runtimes stay on ECR Public: their Docker Hub copies lag by more
 * than a year and some tags are missing there.
 */
public final class TestImages {

    public static final String BUSYBOX = "busybox:stable";

    /**
     * For the one test that pulls the other architecture. A foreign-platform pull replaces what
     * the local tag points at on a daemon without the containerd image store, so it must not share
     * a tag with the tests that run or build from {@link #BUSYBOX} on the host architecture.
     */
    public static final String BUSYBOX_FOREIGN_PLATFORM = "busybox:1.36";

    public static final String PYTHON_ALPINE = "python:3.12-alpine";

    /** The same image AWS publishes at {@code public.ecr.aws/aws-observability/aws-for-fluent-bit:3}. */
    public static final String FLUENT_BIT = "amazon/aws-for-fluent-bit:3";

    /** The tag {@code floci.services.ecr.registry-image} ships with. */
    public static final String REGISTRY = "registry:2";

    private TestImages() {
    }
}
