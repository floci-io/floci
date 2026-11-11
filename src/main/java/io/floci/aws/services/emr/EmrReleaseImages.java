package io.floci.aws.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes, on the Docker daemon, the images a release's clusters run in: its EMR Serverless image with the HDFS,
 * MapReduce and YARN of its EMR Serverless Hive image, which the EMR Serverless image's Hadoop client lacks, and for a
 * cluster with Hive the Hive image's Hive and Tez too, the two being built on one Hadoop and one Java.
 * A container of the EMR Serverless image, never started, gets the Hive image's folders streamed in from another,
 * never started either, and is committed, as a bootstrap's container is. The two images ship different builds of
 * {@code emr-serverless-goodies-common} under one name, so Tez keeps the Hive image's in a folder of its own, its links
 * leading there, and the Hive image's Hadoop configuration, which puts Tez on the class path, goes to
 * {@link EmrServerlessSparkRunner#HIVE_HADOOP_CONF}, for Hive alone. The image is named after its release, the ids of
 * the two images and what is copied, so a newer pull of either, or a change to what is copied, makes another, and it is
 * kept for the release's next cluster. A release of the AWS runtime for Apache Spark has no Hive image, and another
 * release's HDFS and YARN do not run on its newer Hadoop, so its image gets the whole of that release's Hadoop, in a
 * folder apart, which only its primary node's daemons run from.
 */
@ApplicationScoped
public class EmrReleaseImages implements ReleaseImages {

    private static final Logger LOG = Logger.getLogger(EmrReleaseImages.class);
    private static final String SERVICE = "emr";
    private static final String COMPONENT = "emr-release";
    private static final String EMR_SHARE = "/usr/share/aws/emr";
    private static final String GOODIES = EMR_SHARE + "/serverless-goodies/lib";
    private static final String HIVE_GOODIES = "serverless-goodies-hive/lib";
    // What the Hive image adds for a cluster without Hive: the Hadoop services the EMR Serverless image lacks, which its
    // own Hadoop's client links name.
    static final List<Copy> HADOOP_COPIES = List.of(
            new Copy("/usr/lib/hadoop-hdfs", "/usr/lib"),
            new Copy("/usr/lib/hadoop-mapreduce", "/usr/lib"),
            new Copy("/usr/lib/hadoop-yarn", "/usr/lib"));
    // What it adds for a cluster with Hive: Hive and Tez, those Hadoop services, their configuration and commands,
    // EMR's s3-dist-cp among them, and the two folders kept apart.
    static final List<Copy> COPIES = List.of(
            new Copy("/usr/lib/hive", "/usr/lib"),
            new Copy("/usr/lib/tez", "/usr/lib"),
            new Copy("/usr/lib/hadoop-hdfs", "/usr/lib"),
            new Copy("/usr/lib/hadoop-mapreduce", "/usr/lib"),
            new Copy("/usr/lib/hadoop-yarn", "/usr/lib"),
            new Copy("/usr/lib/bigtop-groovy", "/usr/lib"),
            new Copy("/etc/hive", "/etc"),
            new Copy("/etc/tez", "/etc"),
            new Copy("/etc/tez-ui", "/etc"),
            new Copy(EMR_SHARE + "/s3-dist-cp", EMR_SHARE),
            new Copy("/usr/bin/hive", "/usr/bin"),
            new Copy("/usr/bin/beeline", "/usr/bin"),
            new Copy("/usr/bin/hiveserver2", "/usr/bin"),
            new Copy("/usr/bin/hplsql", "/usr/bin"),
            new Copy("/usr/bin/s3-dist-cp", "/usr/bin"),
            Copy.to("/etc/hadoop/conf", EmrServerlessSparkRunner.HIVE_HADOOP_CONF),
            new Copy(GOODIES, EMR_SHARE, HIVE_GOODIES));
    private static final String HADOOP_APART = "/opt";
    // What it adds for a release of the AWS runtime for Apache Spark: another release's Hadoop, whole and kept apart.
    static final List<Copy> HADOOP_APART_COPIES = List.of(
            new Copy("/usr/lib/hadoop", HADOOP_APART),
            new Copy("/usr/lib/hadoop-hdfs", HADOOP_APART),
            new Copy("/usr/lib/hadoop-mapreduce", HADOOP_APART),
            new Copy("/usr/lib/hadoop-yarn", HADOOP_APART));

    // One making at a time for each image; another thread asking for it waits, then finds it there.
    private final Map<String, Object> making = new ConcurrentHashMap<>();

    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerBuilder containerBuilder;
    private final ImageCacheService imageCacheService;
    private final EmulatorConfig config;

    @Inject
    public EmrReleaseImages(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                            ImageCacheService imageCacheService, EmulatorConfig config) {
        this.lifecycleManager = lifecycleManager;
        this.containerBuilder = containerBuilder;
        this.imageCacheService = imageCacheService;
        this.config = config;
    }

    /** The folder of a release's image holding the Hadoop its primary node's daemons run from, or null for its own. */
    static String hadoopFolder(String releaseLabel) {
        return EmrServerlessSparkRunner.sparkRuntime(releaseLabel) ? HADOOP_APART : null;
    }

    /**
     * The image's id: the image is made from the two as a container of either would run them, pulled when missing, and
     * the ids their names lead to now, so a newer pull of either, which moves its name, makes another. A step gets
     * the id, as it gets a bootstrap image's, so the image-registry-base Floci prefixes image names with does not send
     * it to a registry for an image only this daemon has.
     */
    @Override
    public String image(String releaseLabel, String sparkImage, String hiveImage, boolean withHive) {
        List<Copy> copies = hadoopFolder(releaseLabel) != null ? HADOOP_APART_COPIES
                : withHive ? COPIES : HADOOP_COPIES;
        String spark = containerBuilder.resolveImage(sparkImage);
        String hive = containerBuilder.resolveImage(hiveImage);
        String sparkId = imageCacheService.resolveForLaunch(spark, ImagePullBehavior.PREFER_CACHED, null).imageId();
        String hiveId = imageCacheService.resolveForLaunch(hive, ImagePullBehavior.PREFER_CACHED, null).imageId();
        String repository = ContainerStorageHelper.dockerName(config, COMPONENT);
        String tag = releaseLabel + "-" + Digests.sha256Hex(sparkId + "\n" + hiveId + "\n" + copies).substring(0, 12);
        synchronized (making.computeIfAbsent(repository + ":" + tag, name -> new Object())) {
            DockerClient docker = lifecycleManager.getDockerClient();
            try {
                return docker.inspectImageCmd(repository + ":" + tag).exec().getId();
            } catch (NotFoundException e) {
                LOG.infov("Making {0}:{1} for EMR clusters {2}, from {3} and {4}", repository, tag,
                        withHive ? "with Hive" : "without Hive", spark, hive);
            }
            String user = docker.inspectImageCmd(sparkId).exec().getConfig().getUser();
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(ContainerStorageHelper.SERVICE_LABEL, SERVICE);
            labels.put(ContainerStorageHelper.COMPONENT_LABEL, COMPONENT);
            labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
            List<String> containers = new ArrayList<>();
            try {
                String target = docker.createContainerCmd(sparkId).withLabels(labels).exec().getId();
                containers.add(target);
                String source = docker.createContainerCmd(hiveId).withLabels(labels).exec().getId();
                containers.add(source);
                for (Copy copy : copies) {
                    RetryingTarCopier.copyStreamed(docker, target, copy.into(), copy.from(),
                            out -> copy(docker.copyArchiveFromContainerCmd(source, copy.from()).exec(), out, copy));
                }
                return lifecycleManager.commit(target, repository, tag,
                        user == null || user.isBlank() ? "root" : user, labels);
            } finally {
                for (String container : containers) {
                    try {
                        docker.removeContainerCmd(container).withForce(true).exec();
                    } catch (RuntimeException e) {
                        LOG.warnv("Could not remove container {0}, left from making {1}:{2}: {3}", container,
                                repository, tag, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Writes the archive Docker gave of {@code copy}'s source, whose entries start with its name, to {@code out} as
     * {@code copy} puts it in its folder: under its own name, after the folders of one made of several, and with a
     * link into the EMR Serverless image's goodies leading to the Hive image's copy of them instead.
     */
    static void copy(InputStream archive, OutputStream out, Copy copy) throws IOException {
        String name = copy.from().substring(copy.from().lastIndexOf('/') + 1);
        try (InputStream source = archive;
             TarArchiveInputStream in = new TarArchiveInputStream(source);
             TarArchiveOutputStream tar = RetryingTarCopier.newTarStream(out)) {
            for (int slash = copy.as().indexOf('/'); slash > 0; slash = copy.as().indexOf('/', slash + 1)) {
                tar.putArchiveEntry(new TarArchiveEntry(copy.as().substring(0, slash + 1)));
                tar.closeArchiveEntry();
            }
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entry.setName(renamed(entry.getName(), name, copy.as()));
                if (entry.isLink()) {
                    entry.setLinkName(renamed(entry.getLinkName(), name, copy.as()));
                } else if (entry.isSymbolicLink() && entry.getLinkName().startsWith(GOODIES + "/")) {
                    entry.setLinkName(EMR_SHARE + "/" + HIVE_GOODIES + entry.getLinkName().substring(GOODIES.length()));
                }
                tar.putArchiveEntry(entry);
                in.transferTo(tar);
                tar.closeArchiveEntry();
            }
        }
    }

    // An archive's path under the name it was made with, under the copy's instead.
    private static String renamed(String path, String name, String as) {
        return path.equals(name) || path.startsWith(name + "/") ? as + path.substring(name.length()) : path;
    }

    /** A path of the Hive image, a file or a folder, copied into the image made's folder {@code into} as {@code as}. */
    record Copy(String from, String into, String as) {

        /** {@code from} copied into a folder of the image made under its own name. */
        Copy(String from, String into) {
            this(from, into, from.substring(from.lastIndexOf('/') + 1));
        }

        /** {@code from} copied to the path {@code to}, whose folder the image made has. */
        static Copy to(String from, String to) {
            return new Copy(from, to.substring(0, to.lastIndexOf('/')), to.substring(to.lastIndexOf('/') + 1));
        }
    }
}
