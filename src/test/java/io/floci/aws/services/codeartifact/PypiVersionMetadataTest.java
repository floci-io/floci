package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PypiVersionMetadataTest {

    private static final String METADATA_TEXT = "Metadata-Version: 2.1\n"
            + "Name: demo-package\n"
            + "Version: 1.0.0\n"
            + "Requires-Dist: requests (>=2.0.0)\n"
            + "Requires-Dist: a-very-long-dependency-name-that-wraps\n"
            + " (>=1.0,<2.0)\n"
            + "Requires-Dist: colorama; sys_platform == \"win32\"\n"
            + "Requires-Dist: bare-name\n";

    @Test
    void extractWheelMetadataFindsTheDistInfoMetadataEntry() throws IOException {
        byte[] wheel = buildZip("demo_package-1.0.0.dist-info/METADATA", METADATA_TEXT);

        Optional<byte[]> metadata = PypiVersionMetadata.extractWheelMetadata(wheel);

        assertTrue(metadata.isPresent());
        assertEquals(METADATA_TEXT, new String(metadata.get(), StandardCharsets.UTF_8));
    }

    @Test
    void extractWheelMetadataIsEmptyWhenNoDistInfoMetadataEntryExists() throws IOException {
        byte[] wheel = buildZip("demo_package/__init__.py", "# not metadata");

        assertTrue(PypiVersionMetadata.extractWheelMetadata(wheel).isEmpty());
    }

    @Test
    void extractSdistMetadataFindsThePkgInfoEntry() throws IOException {
        byte[] sdist = buildTarGz("demo_package-1.0.0/PKG-INFO", METADATA_TEXT);

        Optional<byte[]> metadata = PypiVersionMetadata.extractSdistMetadata(sdist);

        assertTrue(metadata.isPresent());
        assertEquals(METADATA_TEXT, new String(metadata.get(), StandardCharsets.UTF_8));
    }

    @Test
    void extractSdistMetadataIsEmptyWhenNoPkgInfoEntryExists() throws IOException {
        byte[] sdist = buildTarGz("demo_package-1.0.0/setup.py", "print('hi')");

        assertTrue(PypiVersionMetadata.extractSdistMetadata(sdist).isEmpty());
    }

    @Test
    void parseRequiresDistFoldsContinuationsAndKeepsEnvironmentMarkersVerbatim() {
        List<CodeArtifactService.PackageDependency> dependencies =
                PypiVersionMetadata.parseRequiresDist(METADATA_TEXT.getBytes(StandardCharsets.UTF_8));

        assertEquals(4, dependencies.size());
        assertEquals(new CodeArtifactService.PackageDependency(null, "requests", "Requires-Dist", "(>=2.0.0)"),
                dependencies.get(0));
        assertEquals(new CodeArtifactService.PackageDependency(null, "a-very-long-dependency-name-that-wraps",
                "Requires-Dist", "(>=1.0,<2.0)"), dependencies.get(1));
        assertEquals(new CodeArtifactService.PackageDependency(null, "colorama", "Requires-Dist",
                "; sys_platform == \"win32\""), dependencies.get(2));
        assertEquals(new CodeArtifactService.PackageDependency(null, "bare-name", "Requires-Dist", null),
                dependencies.get(3));
    }

    /**
     * Qodo review finding: a wheel that vendors another package's own dist-info alongside its own
     * (a real setuptools pattern) must not have the vendored, nested entry mistaken for the
     * wheel's own top-level one, even when it appears first in the archive.
     */
    @Test
    void extractWheelMetadataIgnoresAVendoredNestedDistInfoEntry() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("demo_package/_vendor/other-9.0.dist-info/METADATA"));
            zip.write("Name: other\nRequires-Dist: not-the-real-one\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("demo_package-1.0.0.dist-info/METADATA"));
            zip.write(METADATA_TEXT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Optional<byte[]> metadata = PypiVersionMetadata.extractWheelMetadata(out.toByteArray());

        assertTrue(metadata.isPresent());
        assertEquals(METADATA_TEXT, new String(metadata.get(), StandardCharsets.UTF_8));
    }

    @Test
    void extractZipSdistMetadataFindsTheTopLevelPkgInfoEntryAndIgnoresANestedOne() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("demo_package-1.0.0/vendored/other/PKG-INFO"));
            zip.write("Requires-Dist: not-the-real-one\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("demo_package-1.0.0/PKG-INFO"));
            zip.write(METADATA_TEXT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Optional<byte[]> metadata = PypiVersionMetadata.extractZipSdistMetadata(out.toByteArray());

        assertTrue(metadata.isPresent());
        assertEquals(METADATA_TEXT, new String(metadata.get(), StandardCharsets.UTF_8));
    }

    /**
     * Qodo review finding: a small, highly compressible entry ahead of the real metadata entry
     * must not let this reader decompress an effectively unbounded amount of data looking for it.
     * 80 MiB of zeros compresses to only a few hundred bytes, so this stays a fast unit test while
     * still exceeding the reader's own 64 MiB total-scan budget.
     */
    @Test
    void extractWheelMetadataAbortsRatherThanDecompressingAnUnboundedPrecedingEntry() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("huge-padding.bin"));
            zip.write(new byte[80 * 1024 * 1024]);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("demo_package-1.0.0.dist-info/METADATA"));
            zip.write(METADATA_TEXT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] wheel = out.toByteArray();

        assertThrows(IllegalStateException.class, () -> PypiVersionMetadata.extractWheelMetadata(wheel));
    }

    /**
     * Qodo review finding: a `Requires-Dist:`-looking line inside the long description (the
     * message body, after the RFC822 header/body blank-line boundary) must not be reported as a
     * real dependency. {@code Requires-Dist} is literally used as an example string in PyPA's own
     * packaging guide, so this is a real, not contrived, risk.
     */
    @Test
    void parseRequiresDistIgnoresAHeaderLookingLineInsideTheDescriptionBody() {
        String metadata = "Metadata-Version: 2.1\n"
                + "Name: demo-package\n"
                + "Requires-Dist: requests\n"
                + "\n"
                + "A long description that happens to mention Requires-Dist: fake-package as an example.\n";

        List<CodeArtifactService.PackageDependency> dependencies =
                PypiVersionMetadata.parseRequiresDist(metadata.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of(new CodeArtifactService.PackageDependency(null, "requests", "Requires-Dist", null)),
                dependencies);
    }

    private static byte[] buildZip(String entryName, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] buildTarGz(String entryName, String content) throws IOException {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tar.write(tarHeader(entryName, contentBytes.length));
        tar.write(contentBytes);
        int padding = (512 - contentBytes.length % 512) % 512;
        tar.write(new byte[padding]);
        tar.write(new byte[1024]);

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(tar.toByteArray());
        }
        return gzipped.toByteArray();
    }

    private static byte[] tarHeader(String name, int size) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        byte[] sizeOctal = String.format("%011o", size).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(sizeOctal, 0, header, 124, sizeOctal.length);
        header[156] = '0';
        return header;
    }
}
