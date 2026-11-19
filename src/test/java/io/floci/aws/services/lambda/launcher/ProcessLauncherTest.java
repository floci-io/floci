package io.floci.aws.services.lambda.launcher;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.model.LambdaFileSystemConfig;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.runtime.RuntimeApiServer;
import io.floci.aws.services.lambda.runtime.RuntimeApiServerFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisabledOnOs(OS.WINDOWS)
class ProcessLauncherTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:fn";

    @TempDir
    Path tempDir;

    @TempDir
    Path output;

    private RuntimeApiServerFactory runtimeApiServerFactory;
    private RuntimeApiServer runtimeApiServer;
    private LaunchedContainerAwsEnv awsEnv;
    private ContainerLogStreamer logStreamer;
    private LambdaExecutionRoleCredentials roleCredentials;
    private ProcessLauncher launcher;
    private final List<ContainerHandle> launched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runtimeApiServerFactory = mock(RuntimeApiServerFactory.class);
        runtimeApiServer = mock(RuntimeApiServer.class);
        when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        when(runtimeApiServer.getPort()).thenReturn(12000);
        when(runtimeApiServer.stop()).thenReturn(CompletableFuture.completedFuture(null));
        when(runtimeApiServer.close()).thenReturn(CompletableFuture.completedFuture(null));

        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(List.of("AWS_REGION=" + REGION, "AWS_ENDPOINT_URL=http://127.0.0.1:4566"));
        logStreamer = mock(ContainerLogStreamer.class);
        when(logStreamer.generateLogStreamName(anyString())).thenReturn("2026/01/01/[$LATEST]stream");

        roleCredentials = mock(LambdaExecutionRoleCredentials.class);

        launcher = launcherBoundTo("127.0.0.1");
    }

    @AfterEach
    void stopLaunched() {
        launched.forEach(launcher::stop);
    }

    @Test
    void launchRunsTheBootstrapWithOnlyTheLambdaEnvironment() throws IOException {
        Path code = codeDirectory(writeOutput("env", "env") + "exec sleep 30\n", false);
        LambdaFunction fn = function(code);
        fn.setEnvironment(Map.of("GREETING", "hello", "AWS_ACCESS_KEY_ID", "partial"));

        ContainerHandle handle = launch(fn);

        List<String> env = awaitOutput("env");
        assertThat(env).contains(
                "AWS_LAMBDA_RUNTIME_API=127.0.0.1:12000",
                "_HANDLER=function.handler",
                "AWS_LAMBDA_FUNCTION_NAME=fn",
                "AWS_LAMBDA_FUNCTION_VERSION=$LATEST",
                "AWS_ENDPOINT_URL=http://127.0.0.1:4566",
                "GREETING=hello");
        assertThat(env).noneMatch(line -> line.startsWith("AWS_ACCESS_KEY_ID="));
        assertThat(env).noneMatch(line -> line.startsWith("HOME="));
        verify(awsEnv).sdkBaselineEnv(eq(REGION), eq(Optional.empty()), eq("http://127.0.0.1:4566"),
                eq(Optional.empty()), eq(ACCOUNT));
        verify(runtimeApiServer).markContainerStarted();
        assertThat(launcher.isAlive(handle)).isTrue();
        assertThat(handle.isHotReload()).isFalse();
    }

    @Test
    void aZipPackageRunsFromACopyThatStopDeletes() throws IOException {
        Path code = codeDirectory(writeOutput("pwd", "pwd") + "exec sleep 30\n", false);
        Files.writeString(code.resolve("handler.txt"), "v1");
        ContainerHandle handle = launcher.launch(function(code));

        Path copy = Path.of(awaitOutput("pwd").getFirst());
        Files.writeString(code.resolve("handler.txt"), "v2");

        assertThat(copy.toRealPath()).isNotEqualTo(code.toRealPath());
        assertThat(Files.readString(copy.resolve("handler.txt"))).isEqualTo("v1");
        assertThat(Files.isExecutable(code.resolve("bootstrap"))).isFalse();
        launcher.stop(handle);
        assertThat(copy).doesNotExist();
    }

    @Test
    void longOutputWithoutNewlinesIsSplitIntoLogEvents() throws IOException {
        launch(function(codeDirectory("head -c 300000 /dev/zero | tr '\\0' a\necho\nexec sleep 30\n", true)));

        verify(logStreamer, timeout(10_000)).streamToCloudWatchLogsForAccount(eq(ACCOUNT), eq("/aws/lambda/fn"),
                eq("2026/01/01/[$LATEST]stream"), eq(REGION), argThat(event -> event.length() == 256 * 1024));
        verify(logStreamer, timeout(10_000)).streamToCloudWatchLogsForAccount(eq(ACCOUNT), eq("/aws/lambda/fn"),
                eq("2026/01/01/[$LATEST]stream"), eq(REGION),
                argThat(event -> event.length() == 300_000 - 256 * 1024));
    }

    @Test
    void anExecutionRoleSessionReplacesTheFunctionsCredentials() throws IOException {
        SessionCreds session = new SessionCreds("ASIAEXAMPLE", "secret", "token");
        LambdaFunction fn = function(codeDirectory(writeOutput("env", "env") + "exec sleep 30\n", true));
        when(roleCredentials.forFunction(fn)).thenReturn(Optional.of(session));
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), eq(Optional.of(session)), anyString()))
                .thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIAEXAMPLE"));
        fn.setEnvironment(Map.of("AWS_ACCESS_KEY_ID", "user", "AWS_SECRET_ACCESS_KEY", "user",
                "AWS_SESSION_TOKEN", "user"));

        ContainerHandle handle = launcher.launch(fn);
        List<String> env = awaitOutput("env");
        launcher.stop(handle);

        assertThat(env).contains("AWS_ACCESS_KEY_ID=ASIAEXAMPLE");
        assertThat(env).noneMatch(line -> line.endsWith("=user"));
        verify(roleCredentials).unregister(ACCOUNT, "ASIAEXAMPLE");
    }

    @Test
    void aSpecificBindAddressIsTheEndpointTheFunctionUses() throws IOException {
        launcher = launcherBoundTo("192.168.1.10");

        launch(function(codeDirectory("exec sleep 30\n", true)));

        verify(awsEnv).sdkBaselineEnv(eq(REGION), eq(Optional.empty()), eq("http://192.168.1.10:4566"),
                eq(Optional.empty()), eq(ACCOUNT));
    }

    @Test
    void stopKillsChildrenThatIgnoreTermination() throws IOException {
        Path code = codeDirectory("(trap '' TERM; exec sleep 30) &\n"
                + writeOutput("echo $!", "child") + "exec sleep 30\n", true);
        ContainerHandle handle = launcher.launch(function(code));
        long childPid = Long.parseLong(awaitOutput("child").getFirst().strip());

        launcher.stop(handle);

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                ProcessHandle.of(childPid).map(child -> !child.isAlive()).orElse(true));
    }

    @Test
    void stopEndsTheProcessAndReleasesThePort() throws IOException {
        ContainerHandle handle = launcher.launch(function(codeDirectory("exec sleep 30\n", true)));
        assertThat(launcher.isAlive(handle)).isTrue();

        launcher.stop(handle);

        assertThat(launcher.isAlive(handle)).isFalse();
        verify(runtimeApiServer).quiesce();
        verify(runtimeApiServer).close();
        verify(runtimeApiServerFactory).release(runtimeApiServer);
    }

    @Test
    void aBootstrapThatExitsIsReportedAsARuntimeExit() throws IOException {
        launch(function(codeDirectory("exit 3\n", true)));

        verify(runtimeApiServer, timeout(10_000)).handleRuntimeProcessExited(3);
    }

    @Test
    void outputReachesTheFunctionsLogStream() throws IOException {
        launch(function(codeDirectory("echo ready\nexec sleep 30\n", true)));

        verify(logStreamer, timeout(10_000)).ensureLogGroupAndStreamForAccount(ACCOUNT, "/aws/lambda/fn",
                "2026/01/01/[$LATEST]stream", REGION);
        verify(logStreamer, timeout(10_000)).streamToCloudWatchLogsForAccount(ACCOUNT, "/aws/lambda/fn",
                "2026/01/01/[$LATEST]stream", REGION, "ready");
    }

    @Test
    void aBootstrapThatCannotStartReleasesItsRuntimeApiServer() throws IOException {
        Path code = Files.createTempDirectory(tempDir, "code");
        Path bootstrap = code.resolve("bootstrap");
        Files.writeString(bootstrap, "#!/nonexistent/interpreter\n");
        Files.setPosixFilePermissions(bootstrap, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> launcher.launch(function(code))).isInstanceOf(RuntimeException.class);

        verify(runtimeApiServer).stop();
        verify(runtimeApiServerFactory).release(runtimeApiServer);
    }

    @Test
    void hotReloadRunsFromTheHostDirectory() throws IOException {
        Path hostDirectory = codeDirectory("pwd > pwd.tmp && mv pwd.tmp pwd.out\nexec sleep 30\n", true);
        LambdaFunction fn = function(null);
        fn.setHotReloadHostPath(hostDirectory.toString());

        ContainerHandle handle = launch(fn);

        Path pwdFile = hostDirectory.resolve("pwd.out");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(pwdFile));
        assertThat(Path.of(Files.readString(pwdFile).strip()).toRealPath()).isEqualTo(hostDirectory.toRealPath());
        assertThat(handle.isHotReload()).isTrue();
    }

    @Test
    void aNonExecutableHotReloadBootstrapIsRejected() throws IOException {
        LambdaFunction fn = function(null);
        fn.setHotReloadHostPath(codeDirectory("exec sleep 30\n", false).toString());

        assertRejected(fn, "is not executable");
    }

    @Test
    void containerImagesAreRejected() throws IOException {
        LambdaFunction fn = function(codeDirectory("exec sleep 30\n", true));
        fn.setPackageType("Image");

        assertRejected(fn, "container image");
    }

    @Test
    void managedRuntimesAreRejected() throws IOException {
        LambdaFunction fn = function(codeDirectory("exec sleep 30\n", true));
        fn.setRuntime("python3.13");

        assertRejected(fn, "provided.* runtimes only");
    }

    @Test
    void functionsWithLayersAreRejected() throws IOException {
        LambdaFunction fn = function(codeDirectory("exec sleep 30\n", true));
        fn.setLayers(List.of("arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:tools:1"));

        assertRejected(fn, "has layers");
    }

    @Test
    void functionsWithFileSystemsAreRejected() throws IOException {
        LambdaFunction fn = function(codeDirectory("exec sleep 30\n", true));
        fn.setFileSystemConfigs(List.of(new LambdaFileSystemConfig(
                "arn:aws:elasticfilesystem:us-east-1:" + ACCOUNT + ":access-point/fsap-1", "/mnt/data")));

        assertRejected(fn, "mounts a file system");
    }

    @Test
    void aPackageWithoutABootstrapIsRejected() throws IOException {
        Path code = Files.createDirectories(tempDir.resolve("empty"));

        assertRejected(function(code), "has no bootstrap");
    }

    private ProcessLauncher launcherBoundTo(String host) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.regionOf(FUNCTION_ARN)).thenReturn(REGION);
        return new ProcessLauncher(config, regionResolver, runtimeApiServerFactory, awsEnv, logStreamer,
                roleCredentials, host, 4566);
    }

    private ContainerHandle launch(LambdaFunction fn) {
        ContainerHandle handle = launcher.launch(fn);
        launched.add(handle);
        return handle;
    }

    private void assertRejected(LambdaFunction fn, String message) {
        assertThatThrownBy(() -> launcher.launch(fn)).hasMessageContaining(message);
        verify(runtimeApiServerFactory, never()).create();
    }

    /** A shell line that writes {@code command}'s output to the output file {@code name}, all at once. */
    private String writeOutput(String command, String name) {
        Path file = output.resolve(name);
        return command + " > '" + file + ".tmp' && mv '" + file + ".tmp' '" + file + "'\n";
    }

    private List<String> awaitOutput(String name) throws IOException {
        Path file = output.resolve(name);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(file));
        return Files.readAllLines(file);
    }

    private Path codeDirectory(String script, boolean executable) throws IOException {
        Path code = Files.createTempDirectory(tempDir, "code");
        Path bootstrap = code.resolve("bootstrap");
        Files.writeString(bootstrap, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(bootstrap, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
        return code;
    }

    private static LambdaFunction function(Path code) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fn");
        fn.setFunctionArn(FUNCTION_ARN);
        fn.setRuntime("provided.al2023");
        fn.setHandler("function.handler");
        fn.setPackageType("Zip");
        if (code != null) {
            fn.setCodeLocalPath(code.toString());
        }
        return fn;
    }
}
