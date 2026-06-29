package io.github.hectorvent.floci.services.ssm;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class SsmDirectCommandExecutor {

    private static final Logger LOG = Logger.getLogger(SsmDirectCommandExecutor.class);

    private final DockerClient dockerClient;
    private final Ec2Service ec2Service;

    @Inject
    public SsmDirectCommandExecutor(DockerClient dockerClient, Ec2Service ec2Service) {
        this.dockerClient = dockerClient;
        this.ec2Service = ec2Service;
    }

    public Optional<ExecutionResult> executeIfSupported(
            String instanceId,
            String documentName,
            Map<String, List<String>> parameters,
            int timeoutSeconds) {
        if (!supports(instanceId, documentName)) {
            return Optional.empty();
        }

        Instance instance = ec2Service.findInstanceById(instanceId);
        String script = String.join("\n", parameters.getOrDefault("commands", List.of()));
        if (script.isBlank()) {
            return Optional.of(ExecutionResult.success("", "", 0));
        }

        String workingDirectory = parameters.getOrDefault("workingDirectory", List.of(""))
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);

        try {
            return Optional.of(executeInContainer(instance.getDockerContainerId(), script, workingDirectory, timeoutSeconds));
        }
        catch (Exception e) {
            LOG.warnv(e, "SSM direct command failed for instance {0}", instanceId);
            return Optional.of(ExecutionResult.failed("", e.getMessage() != null ? e.getMessage() : e.toString(), 1));
        }
    }

    public boolean supports(String instanceId, String documentName) {
        if (!"AWS-RunShellScript".equals(documentName)) {
            return false;
        }

        Instance instance = ec2Service.findInstanceById(instanceId);
        if (instance == null || instance.getDockerContainerId() == null || instance.getDockerContainerId().isBlank()) {
            return false;
        }
        return ec2Service.isInstanceContainerRunning(instanceId);
    }

    private ExecutionResult executeInContainer(String containerId, String script, String workingDirectory, int timeoutSeconds)
            throws InterruptedException {
        String[] cmd = {"sh", "-c", timeoutWrappedScript(script, timeoutSeconds)};
        var create = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true);
        if (workingDirectory != null) {
            create.withWorkingDir(workingDirectory);
        }

        String execId = create.exec().getId();
        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Instant start = Instant.now();

        ResultCallback<Frame> callback = dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                ByteArrayOutputStream target = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
                try {
                    target.write(payload);
                }
                catch (IOException ignored) {
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                try {
                    stderr.write((throwable.getMessage() != null ? throwable.getMessage() : throwable.toString())
                            .getBytes(StandardCharsets.UTF_8));
                }
                catch (IOException ignored) {
                }
                latch.countDown();
            }
        });

        boolean completed = latch.await(hostTimeoutSeconds(timeoutSeconds), TimeUnit.SECONDS);
        if (!completed) {
            closeQuietly(callback);
            return ExecutionResult.timedOut(
                    stdout.toString(StandardCharsets.UTF_8),
                    "Timed out after " + timeoutSeconds + "s",
                    start);
        }

        Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        int responseCode = exitCode != null ? exitCode.intValue() : 1;
        String standardOutput = stdout.toString(StandardCharsets.UTF_8);
        String standardError = stderr.toString(StandardCharsets.UTF_8);
        if (isTimeoutExitCode(responseCode)) {
            logFailureDiagnostics(containerId);
            return ExecutionResult.timedOut(standardOutput, standardError, start);
        }
        if (responseCode == 0) {
            return ExecutionResult.success(standardOutput, standardError, responseCode, start);
        }
        logFailureDiagnostics(containerId);
        return ExecutionResult.failed(standardOutput, standardError, responseCode, start);
    }

    static String timeoutWrappedScript(String script, int timeoutSeconds) {
        return userScriptCommand(script, timeoutSeconds);
    }

    private static String userScriptCommand(String script, int timeoutSeconds) {
        String command = "sh -c " + shellSingleQuote(script);
        if (timeoutSeconds < 1) {
            return command;
        }
        String timeout = timeoutSeconds + "s";
        return "if command -v timeout >/dev/null 2>&1; then "
                + "timeout --kill-after=1s " + shellSingleQuote(timeout) + " " + command + "; "
                + "else " + command + "; fi";
    }

    static String diagnosticWrappedScript(String script) {
        return "sh -c " + shellSingleQuote(script);
    }

    private void logFailureDiagnostics(String containerId) {
        try {
            String diagnostics = collectFailureDiagnostics(containerId);
            if (!diagnostics.isBlank()) {
                LOG.warnf("SSM direct command failed; compact service diagnostics:%n%s", diagnostics);
            }
        }
        catch (Exception e) {
            LOG.debugv(e, "Unable to collect SSM direct command diagnostics for container {0}", containerId);
        }
    }

    private String collectFailureDiagnostics(String containerId) throws InterruptedException {
        var create = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", failureDiagnosticsScript())
                .withAttachStdout(true)
                .withAttachStderr(true);
        String execId = create.exec().getId();
        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResultCallback<Frame> callback = dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) {
                    return;
                }
                try {
                    output.write(payload);
                }
                catch (IOException ignored) {
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }
        });
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        if (!completed) {
            closeQuietly(callback);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    static String failureDiagnosticsScript() {
        return """
                floci_ssm_redact() {
                  sed -E \\
                    -e 's/(Authorization:[[:space:]]*Bearer[[:space:]]*)[^[:space:]]+/\\1[REDACTED]/Ig' \\
                    -e 's/((password|passwd|secret|token|client[-_ ]?secret)[^=:\\r\\n]{0,80}[=:][[:space:]]*)[^[:space:]]+/\\1[REDACTED]/Ig'
                }
                echo "[floci:ssm] listening ports"
                (ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null || true) | head -40 | floci_ssm_redact
                echo "[floci:ssm] processes"
                (ps -eo pid,ppid,comm,args 2>/dev/null || ps aux 2>/dev/null || true) | head -40 | floci_ssm_redact || true
                log_count=0
                for log in /var/log/*.log /var/log/*/*.log; do
                  [ -f "$log" ] || continue
                  log_count=$((log_count + 1))
                  [ "$log_count" -le 5 ] || break
                  echo "[floci:ssm] log tail: $log"
                  tail -40 "$log" 2>/dev/null | floci_ssm_redact || true
                done
                """;
    }

    private static long hostTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            return 0;
        }
        return timeoutSeconds + 2L;
    }

    private static boolean isTimeoutExitCode(int responseCode) {
        return responseCode == 124 || responseCode == 137 || responseCode == 143;
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        }
        catch (IOException ignored) {
        }
    }

    public record ExecutionResult(
            String status,
            String standardOutput,
            String standardError,
            int responseCode,
            Instant executionStartDateTime,
            Instant executionEndDateTime) {
        static ExecutionResult success(String standardOutput, String standardError, int responseCode) {
            return success(standardOutput, standardError, responseCode, Instant.now());
        }

        static ExecutionResult success(String standardOutput, String standardError, int responseCode, Instant start) {
            return new ExecutionResult("Success", standardOutput, standardError, responseCode, start, Instant.now());
        }

        static ExecutionResult failed(String standardOutput, String standardError, int responseCode) {
            return failed(standardOutput, standardError, responseCode, Instant.now());
        }

        static ExecutionResult failed(String standardOutput, String standardError, int responseCode, Instant start) {
            return new ExecutionResult("Failed", standardOutput, standardError, responseCode, start, Instant.now());
        }

        static ExecutionResult timedOut(String standardOutput, String standardError, Instant start) {
            return new ExecutionResult("TimedOut", standardOutput, standardError, -1, start, Instant.now());
        }
    }
}
