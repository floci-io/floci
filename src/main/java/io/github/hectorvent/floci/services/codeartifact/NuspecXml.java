package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.XmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pulls {@code id}, {@code version}, and {@code description} out of one {@code .nuspec} file's
 * XML, the three elements every valid nuspec's {@code metadata} must declare, using the shared,
 * hardened {@link XmlParser#parseDocument}. A real nuspec declares its elements under a default
 * {@code xmlns} (the exact URI varies by schema version) and never prefixes them, so {@link
 * Document#getElementsByTagName} still matches on the literal tag text even though that parser
 * is namespace-aware: an unprefixed element's qualified name is just its local name.
 */
final class NuspecXml {

    // A real nuspec is at most a few KB; this is generous headroom, not a realistic size, so a
    // crafted entry that decompresses past it is rejected outright rather than exhausting the
    // heap on a request that already passed the overall upload size check (that check measures
    // the compressed request body, not what a ZIP entry inside it can expand to).
    private static final int MAX_NUSPEC_BYTES = 1024 * 1024;

    private NuspecXml() {
    }

    record Metadata(String id, String version, Optional<String> description) {
    }

    /**
     * {@code content} is the raw bytes of a {@code .nuspec} file taken from inside a caller-supplied
     * {@code .nupkg} upload, so this hardens the parser against XXE the same way any other
     * caller-controlled XML parsed in this codebase must: no DOCTYPE at all ({@code
     * disallow-doctype-decl}), which also rules out external entities and external DTDs without
     * needing to disable each individually.
     */
    static Metadata parse(byte[] content) {
        Document document;
        try {
            document = XmlParser.parseDocument(new String(content, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse the uploaded package's .nuspec file.", e);
        }
        Element root = document.getDocumentElement();
        NodeList metadataNodes = root.getElementsByTagName("metadata");
        if (metadataNodes.getLength() == 0) {
            throw new IllegalStateException("nuspec has no <metadata> element.");
        }
        Element metadata = (Element) metadataNodes.item(0);
        String id = requireText(metadata, "id");
        String version = requireText(metadata, "version");
        Optional<String> description = optionalText(metadata, "description");
        return new Metadata(id, version, description);
    }

    private static String requireText(Element metadata, String tagName) {
        return optionalText(metadata, tagName)
                .orElseThrow(() -> new IllegalStateException("nuspec is missing a <" + tagName + "> element."));
    }

    /**
     * A {@code .nupkg} is a plain ZIP archive (not an OPC package; {@code NuGet.Packaging}'s own
     * reader finds the manifest by extension, not by any OPC relationship), so this scans its
     * entries with the JDK's own {@link ZipInputStream} for the one whose name both ends in
     * {@code .nuspec} and has no {@code /} in it: the manifest always sits at the archive root,
     * never nested, which keeps this from matching an unrelated {@code .nuspec}-suffixed entry a
     * crafted upload could otherwise plant inside a subdirectory.
     */
    static Metadata extractFromNupkg(byte[] nupkgBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(nupkgBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.toLowerCase(Locale.ROOT).endsWith(".nuspec") && !name.contains("/")) {
                    return parse(readBounded(zip));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded package as a .nupkg archive.", e);
        }
        throw new IllegalStateException("The uploaded package has no .nuspec manifest at its root.");
    }

    /**
     * Reads the current entry of {@code zip} up to {@link #MAX_NUSPEC_BYTES}, throwing as soon as
     * that limit is crossed rather than after buffering the whole thing: a highly-compressed
     * entry can decompress to far more than the request's own, already-checked, compressed byte
     * count, so the bound has to apply to the decompressed stream itself, not just the upload.
     */
    private static byte[] readBounded(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zip.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_NUSPEC_BYTES) {
                throw new IllegalStateException(
                        "The uploaded package's .nuspec manifest exceeds " + MAX_NUSPEC_BYTES + " bytes decompressed.");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static Optional<String> optionalText(Element metadata, String tagName) {
        NodeList nodes = metadata.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return Optional.empty();
        }
        String text = nodes.item(0).getTextContent();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
}
