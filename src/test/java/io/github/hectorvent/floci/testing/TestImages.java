package io.github.hectorvent.floci.testing;

/**
 * Images the Docker-backed tests start, one constant per image so a tag lives in one place.
 * {@code .github/ci/prefetch-images.sh} reads the values from this file, the way it reads the
 * sidecar pins from {@code application.yml}, so the prefetch cannot drift from the tests.
 */
public final class TestImages {

    public static final String BUSYBOX = "public.ecr.aws/docker/library/busybox:stable";

    /**
     * For the one test that pulls the other architecture. A foreign-platform pull replaces what
     * the local tag points at on a daemon without the containerd image store, so it must not share
     * a tag with the tests that run or build from {@link #BUSYBOX} on the host architecture.
     */
    public static final String BUSYBOX_FOREIGN_PLATFORM = "public.ecr.aws/docker/library/busybox:1.36";

    private TestImages() {
    }
}
