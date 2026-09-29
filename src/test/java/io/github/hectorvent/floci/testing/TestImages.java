package io.github.hectorvent.floci.testing;

/**
 * Images the Docker-backed tests start, one constant per image so a tag lives in one place.
 * {@code .github/ci/prefetch-images.sh} reads the values from this file, the way it reads the
 * sidecar pins from {@code application.yml}, so the prefetch cannot drift from the tests.
 * <p>
 * The Docker library images are named the Docker Hub way, not through the
 * {@code public.ecr.aws/docker/library} mirror. That mirror has an anonymous quota of its own,
 * shared by runner IP and often spent before a job starts: on one CI job the first pull of the
 * job, {@code docker/library/busybox} from that mirror, failed with
 * {@code toomanyrequests: Data limit exceeded}, while the same runner pulled {@code busybox} from
 * Docker Hub seven seconds later and a 116 MiB {@code lambda/} image from ECR Public half a
 * minute after that. GitHub-hosted runners pull Docker Hub without a limit.
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

    private TestImages() {
    }
}
