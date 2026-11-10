package io.floci.aws.testing;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Consumes part of each failed upload and captures the complete successful retry. */
public final class DockerArchiveUploadFixture {
    public final List<InputStream> streams = new ArrayList<>();
    public final List<CopyArchiveToContainerCmd> commands = new ArrayList<>();
    public final List<byte[]> completed = new ArrayList<>();
    public int failures;
    public int consumedBytes;
    public RuntimeException failure;

    public DockerArchiveUploadFixture(DockerClient docker, String containerId) {
        when(docker.copyArchiveToContainerCmd(containerId)).thenAnswer(ignored -> {
            CopyArchiveToContainerCmd command = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
            commands.add(command);
            AtomicReference<InputStream> input = new AtomicReference<>();
            when(command.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
                InputStream stream = invocation.getArgument(0);
                streams.add(stream);
                input.set(stream);
                return command;
            });
            doAnswer(invocation -> {
                if (commands.size() <= failures) {
                    input.get().readNBytes(consumedBytes);
                    throw failure;
                }
                completed.add(input.get().readAllBytes());
                return null;
            }).when(command).exec();
            return command;
        });
    }
}
