package io.floci.aws.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.testing.ThreadStates;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The image a release's clusters with Hive run in, made of its EMR Serverless and EMR Serverless Hive images. */
class EmrReleaseImagesTest {

    private static final String SPARK = "public.ecr.aws/emr-serverless/spark/emr-7.5.0:latest";
    private static final String HIVE = "public.ecr.aws/emr-serverless/hive/emr-7.5.0:latest";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ImageCacheService imageCache = mock(ImageCacheService.class);
    private final EmrReleaseImages images = new EmrReleaseImages(lifecycle, containerBuilder, imageCache, config);

    @Test
    void aCopyPutsItsSourceUnderItsNameInItsFolderAndTezsGoodiesLinksLeadToTheHiveImagesCopy() throws IOException {
        byte[] tez = tar(
                directory("tez/"),
                symlink("tez/tez-emr-serverless.jar",
                        "/usr/share/aws/emr/serverless-goodies/lib/tez-emr-serverless.jar"),
                symlink("tez/conf", "/etc/tez/conf"),
                file("tez/tez-api.jar", "api"),
                hardLink("tez/tez-api-copy.jar", "tez/tez-api.jar"));
        byte[] goodies = tar(directory("lib/"), file("lib/goodies.jar", "hive's"),
                symlink("lib/latest.jar", "goodies.jar"));
        byte[] conf = tar(directory("conf/"), file("conf/hadoop-env.sh", "TEZ"),
                hardLink("conf/env", "conf/hadoop-env.sh"));

        assertEquals(List.of("tez/ dir", "tez/tez-emr-serverless.jar -> "
                        + "/usr/share/aws/emr/serverless-goodies-hive/lib/tez-emr-serverless.jar",
                "tez/conf -> /etc/tez/conf", "tez/tez-api.jar = api", "tez/tez-api-copy.jar => tez/tez-api.jar"),
                entries(copied(tez, new EmrReleaseImages.Copy("/usr/lib/tez", "/usr/lib"))));
        // The Hive image's goodies go to a folder of their own, which the EMR Serverless image does not have.
        assertEquals(List.of("serverless-goodies-hive/ dir", "serverless-goodies-hive/lib/ dir",
                "serverless-goodies-hive/lib/goodies.jar = hive's",
                "serverless-goodies-hive/lib/latest.jar -> goodies.jar"),
                entries(copied(goodies, new EmrReleaseImages.Copy("/usr/share/aws/emr/serverless-goodies/lib",
                        "/usr/share/aws/emr", "serverless-goodies-hive/lib"))));
        assertEquals(List.of("conf.hive/ dir", "conf.hive/hadoop-env.sh = TEZ",
                "conf.hive/env => conf.hive/hadoop-env.sh"),
                entries(copied(conf, EmrReleaseImages.Copy.to("/etc/hadoop/conf", "/etc/hadoop/conf.hive"))));
    }

    @Test
    void theHiveImagesHadoopConfigurationGoesWhereHiveRunsWithIt() {
        assertTrue(EmrReleaseImages.COPIES.contains(new EmrReleaseImages.Copy("/etc/hadoop/conf", "/etc/hadoop",
                "conf.hive")));
    }

    @Test
    void anImageThatIsMissingIsMadeInAContainerOfTheSparkImageNeverStartedThenCommitted() {
        String repository = ContainerStorageHelper.dockerName(config, "emr-release");
        List<String> copied = makesMissing(true);

        assertEquals(EmrReleaseImages.COPIES.stream().map(EmrReleaseImages.Copy::from).toList(), copied);
        // Named after the release and what it is made of, and labelled as Floci's EMR release image.
        verify(lifecycle).commit(eq("target"), eq(repository),
                argThat(tag -> tag.matches("emr-7\\.5\\.0-[0-9a-f]{12}")), eq("hadoop:hadoop"),
                argThat((Map<String, String> labels) ->
                        "emr-release".equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))));
        verify(docker).removeContainerCmd("target");
        verify(docker).removeContainerCmd("source");
    }

    @Test
    void anImageForAClusterWithoutHiveHasHadoopsServicesAloneAndANameOfItsOwn() {
        List<String> copied = makesMissing(false);

        // No Hive, so a Hive step there finds no hive, as EMR's hive-script does without it.
        assertEquals(List.of("/usr/lib/hadoop-hdfs", "/usr/lib/hadoop-mapreduce", "/usr/lib/hadoop-yarn"), copied);
        assertTrue(EmrReleaseImages.COPIES.containsAll(EmrReleaseImages.HADOOP_COPIES));
        ArgumentCaptor<String> tags = ArgumentCaptor.forClass(String.class);
        images.image("emr-7.5.0", SPARK, HIVE, true);
        verify(lifecycle, times(2)).commit(eq("target"), anyString(), tags.capture(), anyString(), any());
        assertNotEquals(tags.getAllValues().get(0), tags.getAllValues().get(1));
    }

    @Test
    void anImageOfTheAwsRuntimeForSparkKeepsAnotherReleasesWholeHadoopApart() {
        List<String> copied = makesMissing("emr-spark-8.0.0", false);

        // Another release's services do not run on its newer Hadoop, so Spark's stays where it is.
        assertEquals(List.of("/usr/lib/hadoop", "/usr/lib/hadoop-hdfs", "/usr/lib/hadoop-mapreduce",
                "/usr/lib/hadoop-yarn"), copied);
        assertEquals("/opt", EmrReleaseImages.hadoopFolder("emr-spark-8.0.0"));
        assertTrue(EmrReleaseImages.HADOOP_APART_COPIES.stream().allMatch(copy -> "/opt".equals(copy.into())));
        verify(lifecycle).commit(eq("target"), anyString(),
                argThat(tag -> tag.matches("emr-spark-8\\.0\\.0-[0-9a-f]{12}")), eq("hadoop:hadoop"), any());
    }

    @Test
    void anImageOfAReleaseWithAHiveImageRunsItsOwnHadoop() {
        assertNull(EmrReleaseImages.hadoopFolder("emr-7.5.0"));
    }

    @Test
    void aSecondThreadAskingWhileTheImageIsMadeWaitsThenFindsItThere() throws InterruptedException {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        resolvesEachToItself();
        String repository = ContainerStorageHelper.dockerName(config, "emr-release");
        AtomicBoolean made = new AtomicBoolean();
        when(docker.inspectImageCmd(anyString())).thenAnswer(invocation -> {
            String image = invocation.getArgument(0);
            InspectImageCmd inspect = mock(InspectImageCmd.class);
            InspectImageResponse response = mock(InspectImageResponse.class, RETURNS_DEEP_STUBS);
            when(response.getId()).thenReturn("sha256:made");
            if (image.startsWith(repository + ":") && !made.get()) {
                when(inspect.exec()).thenThrow(new NotFoundException("missing"));
            } else {
                when(inspect.exec()).thenReturn(response);
            }
            return inspect;
        });
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(create.exec()).thenReturn(mock(CreateContainerResponse.class));
        when(docker.createContainerCmd(anyString())).thenReturn(create);
        when(docker.copyArchiveFromContainerCmd(any(), anyString())).thenAnswer(invocation -> {
            CopyArchiveFromContainerCmd from = mock(CopyArchiveFromContainerCmd.class);
            when(from.exec()).thenAnswer(read -> new ByteArrayInputStream(new byte[0]));
            return from;
        });
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));
        when(docker.removeContainerCmd(any())).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        when(lifecycle.commit(any(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            committing.countDown();
            assertTrue(committed.await(10, TimeUnit.SECONDS));
            made.set(true);
            return "sha256:made";
        });
        Thread first = Thread.ofPlatform().start(() -> images.image("emr-7.5.0", SPARK, HIVE, true));
        assertTrue(committing.await(10, TimeUnit.SECONDS));

        AtomicReference<String> found = new AtomicReference<>();
        Thread second = Thread.ofPlatform().start(() -> found.set(images.image("emr-7.5.0", SPARK, HIVE, true)));
        ThreadStates.awaitBlockedOrDone(second);
        committed.countDown();
        first.join(10_000);
        second.join(10_000);

        // One container of each image, one commit: the second found the image the first made.
        verify(docker, times(2)).createContainerCmd(anyString());
        verify(lifecycle, times(1)).commit(any(), anyString(), anyString(), any(), any());
        assertEquals("sha256:made", found.get());
    }

    @Test
    void anImageThatIsThereIsUsedAsItIs() {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        InspectImageCmd there = mock(InspectImageCmd.class);
        InspectImageResponse made = mock(InspectImageResponse.class);
        when(made.getId()).thenReturn("sha256:made");
        when(there.exec()).thenReturn(made);
        when(docker.inspectImageCmd(anyString())).thenReturn(there);
        resolvesEachToItself();

        assertEquals("sha256:made", images.image("emr-7.5.0", SPARK, HIVE, true));

        verify(docker, never()).createContainerCmd(anyString());
        verify(lifecycle, never()).commit(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void anImageIsNamedAfterTheIdsOfTheImagesItIsMadeOfSoANewerPullMakesAnother() {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        resolves(SPARK, "sha256:spark");
        // The Hive image's name leads to another id once a newer image is pulled under it.
        when(imageCache.resolveForLaunch(HIVE, ImagePullBehavior.PREFER_CACHED, null)).thenReturn(
                new LaunchImage("sha256:hive", null), new LaunchImage("sha256:hive", null),
                new LaunchImage("sha256:newer-hive", null));
        List<String> asked = new ArrayList<>();
        when(docker.inspectImageCmd(anyString())).thenAnswer(invocation -> {
            asked.add(invocation.getArgument(0));
            InspectImageCmd there = mock(InspectImageCmd.class);
            when(there.exec()).thenReturn(mock(InspectImageResponse.class));
            return there;
        });

        images.image("emr-7.5.0", SPARK, HIVE, true);
        images.image("emr-7.5.0", SPARK, HIVE, true);
        images.image("emr-7.5.0", SPARK, HIVE, true);

        assertEquals(asked.get(0), asked.get(1));
        assertNotEquals(asked.get(0), asked.get(2));
    }

    @Test
    void aNewerPullOfAnImageItIsMadeOfMakesAnotherThroughTheImageCache() {
        // A daemon whose local tags a test moves, as a docker pull would, keeping the image a tag leaves, behind a real
        // image cache.
        Map<String, String> tags = new HashMap<>(Map.of(SPARK, "sha256:spark", HIVE, "sha256:hive"));
        Set<String> images = new HashSet<>(tags.values());
        String repository = ContainerStorageHelper.dockerName(config, "emr-release");
        List<String> asked = new ArrayList<>();
        DockerClient daemon = mock(DockerClient.class);
        InfoCmd info = mock(InfoCmd.class);
        when(info.exec()).thenReturn(new Info().withOsType("linux").withArchitecture("amd64"));
        when(daemon.infoCmd()).thenReturn(info);
        when(daemon.inspectImageCmd(anyString())).thenAnswer(invocation -> {
            String reference = invocation.getArgument(0);
            InspectImageCmd inspect = mock(InspectImageCmd.class);
            if (reference.startsWith(repository + ":")) {
                // The image made of them, there whatever its name.
                asked.add(reference);
                when(inspect.exec()).thenReturn(new InspectImageResponse().withId("sha256:made"));
            } else if (tags.containsKey(reference) || images.contains(reference)) {
                when(inspect.exec()).thenReturn(new InspectImageResponse()
                        .withId(tags.getOrDefault(reference, reference)).withOs("linux").withArch("amd64")
                        .withRepoDigests(List.of()));
            } else {
                when(inspect.exec()).thenThrow(new NotFoundException("No such image: " + reference));
            }
            return inspect;
        });
        EmulatorConfig cacheConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(cacheConfig.docker().registryCredentials()).thenReturn(List.of());
        when(cacheConfig.docker().imagePullTimeoutSeconds()).thenReturn(300);
        when(lifecycle.getDockerClient()).thenReturn(daemon);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        EmrReleaseImages cached = new EmrReleaseImages(lifecycle, containerBuilder,
                new ImageCacheService(daemon, cacheConfig), config);

        cached.image("emr-7.5.0", SPARK, HIVE, true);
        tags.put(HIVE, "sha256:newer-hive");
        images.add("sha256:newer-hive");
        cached.image("emr-7.5.0", SPARK, HIVE, true);

        assertNotEquals(asked.get(0), asked.get(1));
    }

    // The two images are resolved as a launch would, their names read again each time, pulled only when missing.
    // Makes a missing image, with Hive or without, from containers whose copies are recorded, and returns the paths
    // copied from the Hive image's.
    private List<String> makesMissing(boolean withHive) {
        return makesMissing("emr-7.5.0", withHive);
    }

    private List<String> makesMissing(String release, boolean withHive) {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        String repository = ContainerStorageHelper.dockerName(config, "emr-release");
        // The two are pulled as a container's image would be, from the image-registry-base it names.
        when(containerBuilder.resolveImage(anyString()))
                .thenAnswer(invocation -> "mirror/" + invocation.getArgument(0));
        resolves("mirror/" + SPARK, "sha256:spark");
        resolves("mirror/" + HIVE, "sha256:hive");
        InspectImageCmd missing = mock(InspectImageCmd.class);
        when(missing.exec()).thenThrow(new NotFoundException("missing"));
        when(docker.inspectImageCmd(argThat((String image) -> image.startsWith(repository + ":"))))
                .thenReturn(missing);
        InspectImageCmd spark = mock(InspectImageCmd.class);
        InspectImageResponse sparkImage = mock(InspectImageResponse.class);
        ContainerConfig sparkConfig = mock(ContainerConfig.class);
        when(sparkConfig.getUser()).thenReturn("hadoop:hadoop");
        when(sparkImage.getConfig()).thenReturn(sparkConfig);
        when(spark.exec()).thenReturn(sparkImage);
        when(docker.inspectImageCmd("sha256:spark")).thenReturn(spark);
        container("sha256:spark", "target");
        container("sha256:hive", "source");
        List<String> copied = new ArrayList<>();
        when(docker.copyArchiveFromContainerCmd(eq("source"), anyString())).thenAnswer(invocation -> {
            copied.add(invocation.getArgument(1));
            CopyArchiveFromContainerCmd from = mock(CopyArchiveFromContainerCmd.class);
            when(from.exec()).thenAnswer(read -> new ByteArrayInputStream(new byte[0]));
            return from;
        });
        when(docker.copyArchiveToContainerCmd("target"))
                .thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));
        when(lifecycle.commit(eq("target"), eq(repository), anyString(), eq("hadoop:hadoop"), any()))
                .thenReturn("sha256:made");
        when(docker.removeContainerCmd(anyString())).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));

        assertEquals("sha256:made", images.image(release, SPARK, HIVE, withHive));
        return copied;
    }

    private void resolves(String image, String id) {
        when(imageCache.resolveForLaunch(image, ImagePullBehavior.PREFER_CACHED, null))
                .thenReturn(new LaunchImage(id, null));
    }

    private void resolvesEachToItself() {
        when(imageCache.resolveForLaunch(anyString(), eq(ImagePullBehavior.PREFER_CACHED), isNull()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
    }

    private void container(String image, String id) {
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn(id);
        when(create.exec()).thenReturn(created);
        when(docker.createContainerCmd(image)).thenReturn(create);
    }

    private static byte[] copied(byte[] archive, EmrReleaseImages.Copy copy) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmrReleaseImages.copy(new ByteArrayInputStream(archive), out, copy);
        return out.toByteArray();
    }

    // Each entry as one line: a folder, a file and its text, a symbolic link and where it leads, or a hard link.
    private static List<String> entries(byte[] archive) throws IOException {
        List<String> entries = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    entries.add(entry.getName() + " dir");
                } else if (entry.isSymbolicLink()) {
                    entries.add(entry.getName() + " -> " + entry.getLinkName());
                } else if (entry.isLink()) {
                    entries.add(entry.getName() + " => " + entry.getLinkName());
                } else {
                    entries.add(entry.getName() + " = " + new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    private static byte[] tar(Entry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = RetryingTarCopier.newTarStream(out)) {
            for (Entry entry : entries) {
                tar.putArchiveEntry(entry.header());
                tar.write(entry.content());
                tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    private static Entry directory(String name) {
        return new Entry(new TarArchiveEntry(name), new byte[0]);
    }

    private static Entry file(String name, String text) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry header = new TarArchiveEntry(name);
        header.setSize(content.length);
        return new Entry(header, content);
    }

    private static Entry symlink(String name, String target) {
        TarArchiveEntry header = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
        header.setLinkName(target);
        return new Entry(header, new byte[0]);
    }

    private static Entry hardLink(String name, String target) {
        TarArchiveEntry header = new TarArchiveEntry(name, TarConstants.LF_LINK);
        header.setLinkName(target);
        return new Entry(header, new byte[0]);
    }

    private record Entry(TarArchiveEntry header, byte[] content) {
    }
}
