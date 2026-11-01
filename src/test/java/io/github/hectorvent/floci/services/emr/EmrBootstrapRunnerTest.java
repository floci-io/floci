package io.github.hectorvent.floci.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.model.Image;
import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.lambda.launcher.BootstrapProcess;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cluster's bootstrap actions run one after the other through {@code docker exec}, as root, in one
 * container of the release's image, which is then kept as the image the cluster's steps run in.
 */
class EmrBootstrapRunnerTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "eu-west-1";
    private static final String CONTAINER = "bootstrap";
    private static final String RELEASE_IMAGE = "public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest";
    private static final String RELEASE_IMAGE_ID = "sha256:" + "1".repeat(64);
    private static final String IMAGE_ID = "sha256:" + "2".repeat(64);
    private static final List<String> ENV = List.of("AWS_ACCESS_KEY_ID=ASIAEXAMPLE", "AWS_ENDPOINT_URL=http://floci");

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainerBuilder builder = mock(ContainerBuilder.class);
    private final ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
    private final LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
    private final LambdaExecutionRoleCredentials roleCredentials = mock(LambdaExecutionRoleCredentials.class);
    private final S3Service s3 = mock(S3Service.class);
    private final EmrBootstrapRunner runner =
            new EmrBootstrapRunner(builder, lifecycle, awsEnv, roleCredentials, s3, config);
    // What the runner put in S3, by bucket and key, gunzipped: read as it is put, as its files then go.
    private final Map<String, String> uploaded = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        when(builder.newContainer(anyString())).thenReturn(spec);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(CONTAINER, Map.of()));
        when(roleCredentials.forRole(any(), any(), any())).thenReturn(Optional.empty());
        when(awsEnv.sdkBaselineEnv(REGION, Optional.empty(), Optional.empty(), ACCOUNT)).thenReturn(ENV);
        // The container runs the release image, under whatever name an image-registry-base gave it.
        when(docker.inspectContainerCmd(CONTAINER).exec().getImageId()).thenReturn(RELEASE_IMAGE_ID);
        when(docker.inspectImageCmd(RELEASE_IMAGE_ID).exec().getConfig().getUser()).thenReturn("hadoop");
        when(docker.inspectImageCmd(ContainerStorageHelper.dockerName(config, "emr-bootstrap") + ":j-1").exec()
                .getId()).thenReturn(IMAGE_ID);
        script("bucket", "bootstrap/install.sh");
        script("bucket", "bootstrap/jars.sh");
        when(s3.putObjectFromStream(anyString(), anyString(), any(InputStream.class), eq("application/x-gzip"),
                anyMap())).thenAnswer(put -> {
                    try (GZIPInputStream gunzip = new GZIPInputStream(put.<InputStream>getArgument(2))) {
                        uploaded.put(put.getArgument(0) + "/" + put.getArgument(1),
                                new String(gunzip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    return null;
                });
    }

    @Test
    void eachActionRunsInTurnAsRootThenTheContainerIsKeptAsTheImageTheStepsRunIn() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "installed\n", "");

        BootstrapRunner.Outcome outcome = runner.run(bootstrap("s3://logs/j-1/node/i-1/bootstrap-actions/",
                action("s3://bucket/bootstrap/install.sh", "pandas", "2.2"),
                action("s3n://bucket/bootstrap/jars.sh")), () -> false);

        // The steps get the kept image's id, which runs as it is, whatever registry Floci pulls images from.
        assertEquals(BootstrapRunner.Outcome.succeeded(IMAGE_ID), outcome);
        verify(builder).newContainer(RELEASE_IMAGE);
        verify(spec).withUser("root");
        verify(spec).withEntrypoint(List.of("sleep", "infinity"));
        verify(spec).withLabels(argThat(labels -> "emr".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))
                && "j-1".equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))
                && "emr-bootstrap".equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))));
        // Each script is read as the cluster's account and copied in, then run with its arguments.
        verify(s3).getObject("bucket", "bootstrap/install.sh");
        verify(s3).getObject("bucket", "bootstrap/jars.sh");
        assertEquals(List.of("bootstrap-action", "/tmp/floci-bootstrap-action-1-install.sh", "pandas", "2.2"),
                commands.get(0).subList(3, commands.get(0).size()));
        assertEquals(List.of("bootstrap-action", "/tmp/floci-bootstrap-action-2-jars.sh"),
                commands.get(1).subList(3, commands.get(1).size()));
        assertEquals(List.of("bash", "-c"), commands.get(0).subList(0, 2));
        assertTrue(commands.get(0).get(2).contains("chown -R hadoop:hadoop /home/hadoop"));
        assertEquals(3, commands.size());
        // The credentials go to each action's exec alone, never to the container the image is made of.
        ExecCreateCmd exec = docker.execCreateCmd(CONTAINER);
        verify(exec, times(2)).withEnv(ENV);
        verify(spec, never()).withEnv(any(List.class));
        // The image runs as the user of the image the container ran, as the steps do.
        String repository = ContainerStorageHelper.dockerName(config, "emr-bootstrap");
        verify(lifecycle).commit(eq(CONTAINER), eq(repository), eq("j-1"), eq("hadoop"),
                argThat(labels -> "emr-bootstrap".equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))));
        assertEquals(Map.of("logs/j-1/node/i-1/bootstrap-actions/1/stdout.gz", "installed\n",
                "logs/j-1/node/i-1/bootstrap-actions/1/stderr.gz", "",
                "logs/j-1/node/i-1/bootstrap-actions/2/stdout.gz", "installed\n",
                "logs/j-1/node/i-1/bootstrap-actions/2/stderr.gz", ""), uploaded);
        verify(lifecycle).stopAndRemove(CONTAINER, null, 0);
    }

    @Test
    void theActionsJvmsTrustFlocisCaWhoseTrustStoreIsNotKeptInTheImage() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        List<Integer> execsBeforeTheCopy = new ArrayList<>();
        when(lifecycle.copyJavaTrustStore(CONTAINER)).thenAnswer(copy -> {
            execsBeforeTheCopy.add(commands.size());
            return true;
        });

        runner.run(bootstrap(null, action("s3://bucket/bootstrap/install.sh")), () -> false);

        // In once the container is up, before the first action runs, which points its JVMs at it.
        assertEquals(List.of(0), execsBeforeTheCopy);
        assertTrue(commands.get(0).get(2).startsWith(ContainerCaBundle.JAVA_TRUST_SHELL_LINE + "\n"));
        // Each step's container gets its own, so the image goes without it.
        assertEquals(2, commands.size());
        assertTrue(commands.get(1).get(2).contains("rm -f ") && commands.get(1).get(2).contains(
                " " + ContainerCaBundle.JAVA_TRUST_STORE_PATH + ";"), commands.get(1).get(2));
    }

    @Test
    void aFailingActionEndsTheBootstrapThereAndNothingIsKept() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, List.of(0L, 3L), "", "");

        BootstrapRunner.Outcome outcome = runner.run(bootstrap(null, action("s3://bucket/bootstrap/install.sh"),
                action("s3://bucket/bootstrap/jars.sh"), action("s3://bucket/bootstrap/never.sh")), () -> false);

        assertEquals(BootstrapRunner.Outcome.actionFailed(2, 3), outcome);
        assertEquals(2, commands.size());
        verify(lifecycle, never()).commit(any(), any(), any(), any(), any());
        assertTrue(uploaded.isEmpty());
        verify(lifecycle).stopAndRemove(CONTAINER, null, 0);
    }

    @Test
    void anActionsOutputReachesItsLogsWholeHoweverLongAndLeavesNoFileBehind() throws IOException {
        // Megabytes of output, as a chatty pip install gives, which is kept on disk as it arrives, not in memory.
        String output = IntStream.range(0, 2_000).mapToObj(line -> "Collecting package-" + line + " "
                + "x".repeat(1_000) + "\n").collect(Collectors.joining());
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, output, "WARNING: no newline at the end");
        Set<Path> filesBefore = bootstrapFiles();

        runner.run(bootstrap("s3://logs/j-1/node/i-1/bootstrap-actions/", action("s3://bucket/bootstrap/install.sh")),
                () -> false);

        assertEquals(output, uploaded.get("logs/j-1/node/i-1/bootstrap-actions/1/stdout.gz"));
        assertEquals("WARNING: no newline at the end", uploaded.get("logs/j-1/node/i-1/bootstrap-actions/1/stderr.gz"));
        assertEquals(filesBefore, bootstrapFiles());
    }

    @Test
    void aScriptThatCannotBeReadFailsItsActionWithoutRunningIt() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        when(s3.getObject("bucket", "bootstrap/missing.sh"))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        BootstrapRunner.Outcome outcome = runner.run(bootstrap(null, action("s3://bucket/bootstrap/missing.sh")),
                () -> false);

        assertEquals(BootstrapRunner.Outcome.notDownloaded(1, "The specified key does not exist."), outcome);
        assertTrue(commands.isEmpty());
    }

    @Test
    void aClusterThatEndsWhileAnActionRunsStopsTheBootstrapThere() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        AtomicBoolean ended = new AtomicBoolean();
        when(s3.getObject("bucket", "bootstrap/install.sh")).thenAnswer(read -> {
            ended.set(true);
            return content("#!/bin/bash\n");
        });

        BootstrapRunner.Outcome outcome = runner.run(bootstrap(null, action("s3://bucket/bootstrap/install.sh"),
                action("s3://bucket/bootstrap/jars.sh")), ended::get);

        assertEquals(BootstrapRunner.Outcome.halted(), outcome);
        assertEquals(1, commands.size());
        verify(lifecycle, never()).commit(any(), any(), any(), any(), any());
    }

    @Test
    void aClusterThatEndsWhileTheTrustStoreIsCopiedRunsNoAction() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        AtomicBoolean ended = new AtomicBoolean();
        when(lifecycle.copyJavaTrustStore(CONTAINER)).thenAnswer(copy -> {
            ended.set(true);
            return true;
        });

        BootstrapRunner.Outcome outcome = runner.run(bootstrap(null, action("s3://bucket/bootstrap/install.sh")),
                ended::get);

        assertEquals(BootstrapRunner.Outcome.halted(), outcome);
        assertTrue(commands.isEmpty());
        verify(lifecycle, never()).commit(any(), any(), any(), any(), any());
        verify(lifecycle).stopAndRemove(CONTAINER, null, 0);
    }

    @Test
    void imagesOfClustersThatAreGoneAreRemovedAndTheOthersKept() {
        String goneId = "sha256:" + "3".repeat(64);
        Image kept = mock(Image.class);
        when(kept.getId()).thenReturn(IMAGE_ID);
        Image gone = mock(Image.class);
        when(gone.getId()).thenReturn(goneId);
        when(docker.listImagesCmd().withLabelFilter(anyMap()).exec()).thenReturn(List.of(kept, gone));

        runner.removeImages(Set.of(IMAGE_ID));

        verify(lifecycle).removeImage(goneId);
        verify(lifecycle, never()).removeImage(IMAGE_ID);
    }

    @Test
    void sudoDropsItsOptionsAndRunsItsCommandAsItIs(@TempDir Path directory) throws Exception {
        Path sudo = directory.resolve("sudo");
        Files.writeString(sudo, EmrBootstrapRunner.SUDO + "\n");

        BootstrapProcess.Result ended = BootstrapProcess.run(new ProcessBuilder("bash", sudo.toString(), "-E", "-u",
                "root", "--", "GREETING=hello", "sh", "-c", "printf '%s %s|' \"$GREETING\" \"$1\"", "sh", "two words"));

        assertEquals(new BootstrapProcess.Result(0, "hello two words|"), ended);
    }

    // The files an action's output is gzipped into while it runs, in the temporary directory.
    private static Set<Path> bootstrapFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(file -> file.getFileName().toString().startsWith("floci-emr-bootstrap-"))
                    .collect(Collectors.toSet());
        }
    }

    private void script(String bucket, String key) {
        S3Object object = content("#!/bin/bash\necho installed\n");
        when(s3.getObject(bucket, key)).thenReturn(object);
    }

    private static S3Object content(String script) {
        S3Object object = mock(S3Object.class);
        when(object.getData()).thenReturn(script.getBytes(StandardCharsets.UTF_8));
        return object;
    }

    private static BootstrapRunner.Action action(String path, String... args) {
        return new BootstrapRunner.Action(path.substring(path.lastIndexOf('/') + 1), path, List.of(args));
    }

    private static BootstrapRunner.Bootstrap bootstrap(String logFolder, BootstrapRunner.Action... actions) {
        return new BootstrapRunner.Bootstrap("j-1", ACCOUNT, REGION, "emr-7.5.0", null, null,
                "arn:aws:iam::" + ACCOUNT + ":role/emr-ec2", List.of(actions), logFolder);
    }
}
