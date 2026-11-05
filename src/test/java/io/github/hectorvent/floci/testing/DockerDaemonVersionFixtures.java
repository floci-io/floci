package io.github.hectorvent.floci.testing;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.VersionComponent;
import com.github.dockerjava.api.model.VersionPlatform;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class DockerDaemonVersionFixtures {
    private DockerDaemonVersionFixtures() {
    }

    public static void stubDaemonVersion(DockerClient dockerClient, String platformName,
                                         List<VersionComponent> components) {
        VersionCmd versionCmd = mock(VersionCmd.class);
        Version version = mock(Version.class);
        when(dockerClient.versionCmd()).thenReturn(versionCmd);
        when(versionCmd.exec()).thenReturn(version);
        when(version.getPlatform()).thenReturn(new VersionPlatform().withName(platformName));
        when(version.getComponents()).thenReturn(components);
    }
}
