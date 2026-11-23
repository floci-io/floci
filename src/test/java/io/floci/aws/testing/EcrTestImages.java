package io.floci.aws.testing;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.Digests;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A minimal image that a k3s node can pull from Floci ECR and run: one layer holding a tiny static executable at
 * {@code /bin/sleep} that sleeps without end at no CPU, built for the Docker daemon's architecture, pushed through the
 * registry API as {@code docker push} would.
 */
public final class EcrTestImages {

    private static final Logger LOG = Logger.getLogger(EcrTestImages.class);

    private EcrTestImages() {
    }

    /**
     * Pushes the image to {@code repo} as {@code tag}, sending {@code host}, one of Floci's repository hosts, as the
     * {@code Host} header. The repository must exist: ECR refuses a push to one nobody created.
     */
    public static void pushSleepImage(DockerClient dockerClient, String host, String repo, String tag)
            throws IOException {
        boolean arm64 = isArm64(dockerClient);
        byte[] executable = sleepExecutable(arm64);
        byte[] tarBytes;
        try (ByteArrayOutputStream tarOut = new ByteArrayOutputStream();
             TarArchiveOutputStream tar = new TarArchiveOutputStream(tarOut)) {
            TarArchiveEntry entry = new TarArchiveEntry("bin/sleep");
            entry.setMode(0755);
            entry.setSize(executable.length);
            tar.putArchiveEntry(entry);
            tar.write(executable);
            tar.closeArchiveEntry();
            tar.finish();
            tarBytes = tarOut.toByteArray();
        }
        ByteArrayOutputStream gzOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzOut)) {
            gzip.write(tarBytes);
        }
        byte[] layerBytes = gzOut.toByteArray();
        String diffId = "sha256:" + Digests.sha256Hex(tarBytes);
        String layerDigest = "sha256:" + Digests.sha256Hex(layerBytes);

        byte[] configBytes = ("{\"architecture\":\"" + (arm64 ? "arm64" : "amd64") + "\",\"os\":\"linux\",\"config\":{},"
                + "\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[\"" + diffId + "\"]}}")
                .getBytes(StandardCharsets.UTF_8);
        String configDigest = "sha256:" + Digests.sha256Hex(configBytes);

        uploadBlob(host, repo, layerDigest, layerBytes);
        uploadBlob(host, repo, configDigest, configBytes);

        String manifestJson = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": %d,
                    "digest": "%s"
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": %d,
                      "digest": "%s"
                    }
                  ]
                }
                """.formatted(configBytes.length, configDigest, layerBytes.length, layerDigest);

        given().header("Host", host)
                .contentType("application/vnd.docker.distribution.manifest.v2+json")
                .body(manifestJson)
                .when().put("/v2/" + repo + "/manifests/" + tag)
                .then().statusCode(201);
    }

    private static boolean isArm64(DockerClient dockerClient) {
        try {
            String architecture = dockerClient.infoCmd().exec().getArchitecture();
            return architecture != null && (architecture.contains("aarch64") || architecture.contains("arm64"));
        } catch (RuntimeException e) {
            LOG.warnv("Could not read the Docker daemon's architecture, so the image is built for amd64: {0}",
                    e.getMessage());
            return false;
        }
    }

    private static byte[] sleepExecutable(boolean arm64) {
        byte[] code;
        short machine;
        if (arm64) {
            // aarch64: sys_ppoll(NULL, 0, NULL, NULL, 0) (syscall 73) sleeps indefinitely at 0% CPU
            code = new byte[]{
                    0x00, 0x00, (byte) 0x80, (byte) 0xd2, // mov x0, #0
                    0x01, 0x00, (byte) 0x80, (byte) 0xd2, // mov x1, #0
                    0x02, 0x00, (byte) 0x80, (byte) 0xd2, // mov x2, #0
                    0x03, 0x00, (byte) 0x80, (byte) 0xd2, // mov x3, #0
                    0x04, 0x00, (byte) 0x80, (byte) 0xd2, // mov x4, #0
                    0x28, 0x09, (byte) 0x80, (byte) 0xd2, // mov x8, #73
                    0x01, 0x00, 0x00, (byte) 0xd4,       // svc #0
                    (byte) 0xf9, (byte) 0xff, (byte) 0xff, 0x17  // b -7
            };
            machine = (short) 0xB7;
        } else {
            // x86_64: sys_pause() (syscall 34) sleeps indefinitely at 0% CPU
            code = new byte[]{
                    (byte) 0xb8, 0x22, 0x00, 0x00, 0x00, // mov eax, 34
                    0x0f, 0x05,                         // syscall
                    (byte) 0xeb, (byte) 0xf7            // jmp -9
            };
            machine = (short) 0x3E;
        }
        long baseAddr = 0x400000L;
        long entryPoint = baseAddr + 0x78L;
        int fileSize = 0x78 + code.length;

        ByteBuffer buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        // ELF header (64 bytes)
        buf.put(new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0});
        buf.put(new byte[8]); // padding
        buf.putShort((short) 2); // ET_EXEC
        buf.putShort(machine);
        buf.putInt(1); // EV_CURRENT
        buf.putLong(entryPoint);
        buf.putLong(64L); // e_phoff
        buf.putLong(0L); // e_shoff
        buf.putInt(0); // e_flags
        buf.putShort((short) 64); // e_ehsize
        buf.putShort((short) 56); // e_phentsize
        buf.putShort((short) 1); // e_phnum
        buf.putShort((short) 0); // e_shentsize
        buf.putShort((short) 0); // e_shnum
        buf.putShort((short) 0); // e_shstrndx

        // Program header (56 bytes)
        buf.putInt(1); // PT_LOAD
        buf.putInt(7); // PF_R | PF_W | PF_X
        buf.putLong(0L); // p_offset
        buf.putLong(baseAddr); // p_vaddr
        buf.putLong(baseAddr); // p_paddr
        buf.putLong(fileSize); // p_filesz
        buf.putLong(fileSize); // p_memsz
        buf.putLong(0x1000L); // p_align

        // Code
        buf.put(code);
        return buf.array();
    }

    private static void uploadBlob(String host, String repo, String digest, byte[] content) {
        String location = given().header("Host", host)
                .when().post("/v2/" + repo + "/blobs/uploads/")
                .then().statusCode(202)
                .extract().header("Location");
        assertNotNull(location);
        String separator = location.contains("?") ? "&" : "?";
        given().header("Host", host)
                // The Location header already carries a percent-encoded _state token; RestAssured's
                // default encoding would double-encode it and corrupt the token.
                .urlEncodingEnabled(false)
                .contentType("application/octet-stream")
                .body(content)
                .when().put(location + separator + "digest=" + digest)
                .then().statusCode(201);
    }
}
