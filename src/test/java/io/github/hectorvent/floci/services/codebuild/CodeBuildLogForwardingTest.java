package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CodeBuild build runs in a {@code docker exec} stream, not as container PID 1, so the
 * PID-1 log tap ({@link ContainerLogStreamer#attach}) captures nothing and the build's
 * CloudWatch log stream comes back empty. The phase session parses every exec line, so it
 * is the correct place to forward build output to CloudWatch Logs — mirroring how Lambda
 * forwards its exec output. Without this, failed builds are undiagnosable post-hoc.
 */
class CodeBuildLogForwardingTest {

    /** Captures what would be sent to CloudWatch without needing a real logs backend. */
    private static final class CapturingStreamer extends ContainerLogStreamer {
        final List<String> forwarded = new CopyOnWriteArrayList<>();

        CapturingStreamer() {
            super(null, null);
        }

        @Override
        public void streamToCloudWatchLogs(String logGroup, String logStream, String region, String line) {
            forwarded.add(line);
        }
    }

    private static Build build(String id) {
        Build build = new Build();
        build.setId(id);
        build.setPhases(new CopyOnWriteArrayList<>());
        return build;
    }

    private CodeBuildRunner runnerWith(ContainerLogStreamer streamer) {
        return new CodeBuildRunner(null, null, null, streamer, null, null, null, null, null, null);
    }

    @Test
    void buildOutputLinesAreForwardedToCloudWatchVerbatim() {
        CapturingStreamer streamer = new CapturingStreamer();
        CodeBuildRunner runner = runnerWith(streamer);

        CodeBuildRunner.PhaseSession session = runner.newPhaseSession(
                build("log-fwd:1"), "/aws/codebuild/AWSAccelerator-ToolkitProject",
                "2026/01/01/AWSAccelerator-ToolkitProject/1", "us-east-1");

        session.accept("compiling widget\n".getBytes(StandardCharsets.UTF_8));
        session.accept("build succeeded\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("compiling widget", "build succeeded"), streamer.forwarded);
    }

    @Test
    void phaseSentinelLinesAreNotForwarded() {
        CapturingStreamer streamer = new CapturingStreamer();
        CodeBuildRunner runner = runnerWith(streamer);

        CodeBuildRunner.PhaseSession session = runner.newPhaseSession(
                build("log-fwd:2"), "/aws/codebuild/P", "2026/01/01/P/2", "us-east-1");

        // Protocol sentinels bracket phases in the exec stream; they must not pollute the
        // forwarded CloudWatch output, which should mirror real CodeBuild logs. Literal
        // kept in sync with PHASE_START_SENTINEL / PHASE_END_SENTINEL in CodeBuildRunner.
        session.accept("___FLOCI_PHASE_START___ BUILD\n".getBytes(StandardCharsets.UTF_8));
        session.accept("real build line\n".getBytes(StandardCharsets.UTF_8));
        session.accept("___FLOCI_PHASE_END___ BUILD 0\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(streamer.forwarded.contains("real build line"), streamer.forwarded.toString());
        assertTrue(streamer.forwarded.stream().noneMatch(l -> l.contains("___FLOCI_PHASE_")),
                "sentinel lines leaked into CloudWatch stream: " + streamer.forwarded);
    }
}
