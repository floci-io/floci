package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Optional;

/** What Floci reads about the Docker daemon it drives. */
public final class DockerDaemon {

    private static final Logger LOG = Logger.getLogger(DockerDaemon.class);

    private DockerDaemon() {
    }

    /**
     * The daemon's {@code OperatingSystem} from {@code GET /info}, lower-cased, such as
     * {@code docker desktop} or {@code ubuntu 24.04.1 lts}; empty when the daemon cannot be asked.
     */
    public static Optional<String> operatingSystem(DockerClient dockerClient) {
        try {
            String os = dockerClient.infoCmd().exec().getOperatingSystem();
            return Optional.ofNullable(os).map(value -> value.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            LOG.debugv("Could not read the Docker daemon's operating system: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    public static boolean isDockerDesktop(String operatingSystem) {
        return operatingSystem.contains("docker desktop");
    }
}
