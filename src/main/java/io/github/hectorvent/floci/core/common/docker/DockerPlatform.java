package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Info;

import java.util.Locale;

/** Platform selection shared by native helper builds and workload image resolution. */
public final class DockerPlatform {
    private DockerPlatform() {}

    public static String daemon(DockerClient dockerClient) {
        Info info = dockerClient.infoCmd().exec();
        String architecture = info.getArchitecture();
        if (architecture == null || architecture.isBlank()) {
            throw new DockerClientException("Docker did not report its architecture");
        }
        String os = info.getOsType();
        if (os == null || os.isBlank()) {
            os = "linux";
        }
        String normalizedArchitecture = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "aarch64" -> "arm64";
            case "x86_64" -> "amd64";
            default -> architecture.toLowerCase(Locale.ROOT);
        };
        return os.toLowerCase(Locale.ROOT) + "/" + normalizedArchitecture;
    }

    public static boolean matches(InspectImageResponse image, String platform) {
        if (image == null) {
            return false;
        }
        if (platform == null) {
            return true;
        }
        String[] parts = platform.split("/", 2);
        return parts.length == 2 && parts[0].equals(image.getOs()) && parts[1].equals(image.getArch());
    }
}
