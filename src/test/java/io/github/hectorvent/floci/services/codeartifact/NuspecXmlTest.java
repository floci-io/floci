package io.github.hectorvent.floci.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NuspecXmlTest {

    private static final String NUSPEC = "<?xml version=\"1.0\"?>"
            + "<package xmlns=\"http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd\">"
            + "<metadata><id>Demo.Package</id><version>1.0.0</version><authors>me</authors>"
            + "<description>A description.</description></metadata></package>";

    @Test
    void parseReadsIdVersionAndDescriptionThroughTheDefaultNamespace() {
        NuspecXml.Metadata metadata = NuspecXml.parse(NUSPEC.getBytes(StandardCharsets.UTF_8));

        assertEquals("Demo.Package", metadata.id());
        assertEquals("1.0.0", metadata.version());
        assertEquals(Optional.of("A description."), metadata.description());
    }

    @Test
    void parseRejectsAMissingRequiredElement() {
        String nuspec = "<?xml version=\"1.0\"?><package><metadata><id>Demo</id></metadata></package>";

        assertThrows(IllegalStateException.class, () -> NuspecXml.parse(nuspec.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A DOCTYPE declaration is rejected outright rather than silently resolved: this is the one
     * observable way to confirm external entity expansion is actually disabled, since a
     * successful parse of a benign payload alone wouldn't distinguish "XXE is disabled" from
     * "this particular payload didn't need an external entity anyway."
     */
    @Test
    void parseRejectsADoctypeDeclarationRatherThanResolvingIt() {
        String maliciousNuspec = "<?xml version=\"1.0\"?><!DOCTYPE package [<!ENTITY xxe SYSTEM \"file:///etc/"
                + "hostname\">]><package><metadata><id>&xxe;</id><version>1.0.0</version></metadata></package>";

        assertThrows(IllegalStateException.class,
                () -> NuspecXml.parse(maliciousNuspec.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void extractFromNupkgFindsTheRootNuspecEntryInsideTheZip() throws Exception {
        byte[] nupkg = buildNupkg(NUSPEC);

        NuspecXml.Metadata metadata = NuspecXml.extractFromNupkg(nupkg);

        assertEquals("Demo.Package", metadata.id());
    }

    /** The manifest must sit at the archive root; a same-suffix entry nested in a subdirectory doesn't count. */
    @Test
    void extractFromNupkgIgnoresANestedNuspecSuffixedEntry() {
        byte[] nupkg = buildNupkg(null);

        assertThrows(IllegalStateException.class, () -> NuspecXml.extractFromNupkg(nupkg));
    }

    /**
     * Found by Qodo review: a highly-compressible entry can expand to far more than the overall
     * upload's own, already-checked, compressed byte count, so the bound has to apply to the
     * decompressed stream, not just the request body. The repeated character below compresses to
     * a tiny fraction of its real size, matching exactly that shape.
     */
    @Test
    void extractFromNupkgRejectsANuspecEntryThatDecompressesPastTheSizeLimit() {
        String oversizedDescription = "A".repeat(2 * 1024 * 1024);
        String nuspec = "<?xml version=\"1.0\"?><package><metadata><id>Demo</id><version>1.0.0</version>"
                + "<description>" + oversizedDescription + "</description></metadata></package>";
        byte[] nupkg = buildNupkg(nuspec);

        assertThrows(IllegalStateException.class, () -> NuspecXml.extractFromNupkg(nupkg));
    }

    private static byte[] buildNupkg(String rootNuspecOrNull) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                if (rootNuspecOrNull != null) {
                    zip.putNextEntry(new ZipEntry("Demo.Package.nuspec"));
                    zip.write(rootNuspecOrNull.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                } else {
                    zip.putNextEntry(new ZipEntry("package/services/metadata/core-properties/fake.nuspec"));
                    zip.write("not a real manifest".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
