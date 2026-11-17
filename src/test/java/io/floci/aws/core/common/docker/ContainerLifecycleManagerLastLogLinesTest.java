package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerLastLogLinesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private EmulatorConfig config;

    private final LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
        when(dockerClient.logContainerCmd("c1")).thenReturn(logs);
    }

    @Test
    void readsTheLastLinesOfBothStreams() {
        when(logs.exec(any())).thenAnswer(reading -> {
            ResultCallback.Adapter<Frame> reader = reading.getArgument(0);
            reader.onNext(new Frame(StreamType.STDOUT, "one\n".getBytes(UTF_8)));
            reader.onNext(new Frame(StreamType.STDERR, "two\n".getBytes(UTF_8)));
            reader.onComplete();
            return reader;
        });

        assertEquals("one\ntwo", manager.lastLogLines("c1", 40, TIMEOUT));

        verify(logs).withStdOut(true);
        verify(logs).withStdErr(true);
        verify(logs).withTail(40);
    }

    @Test
    void aLogDockerCannotGiveIsEmptyRatherThanAFailure() {
        when(logs.exec(any())).thenThrow(new NotFoundException("gone"));

        assertEquals("", manager.lastLogLines("c1", 40, TIMEOUT));
    }
}
