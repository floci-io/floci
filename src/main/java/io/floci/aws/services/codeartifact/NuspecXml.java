package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.XmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** One {@code <dependency id="" version=""/>} entry, verbatim, before any format mapping. */
    record RawDependency(String id, String version) {
    }

    /**
     * Every direct dependency declared under {@code <metadata>/<dependencies>}, flattened across
     * however many {@code <group targetFramework="...">} blocks the nuspec has (AWS's own
     * {@code PackageDependency} shape carries no target-framework field, so the same package id
     * can legitimately appear more than once with a different {@code version} across frameworks;
     * that is correct, AWS-matching behavior, not something to deduplicate). Also handles the
     * older nuspec schema's bare {@code <dependency>} directly under {@code <dependencies>} with
     * no {@code <group>} wrapper at all. Empty when the nuspec declares no dependencies.
     */
    static List<RawDependency> dependencies(byte[] content) {
        Document document;
        try {
            document = XmlParser.parseDocument(content);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse the package version's .nuspec file.", e);
        }
        NodeList metadataNodes = document.getDocumentElement().getElementsByTagName("metadata");
        if (metadataNodes.getLength() == 0) {
            return List.of();
        }
        NodeList dependenciesNodes = ((Element) metadataNodes.item(0)).getElementsByTagName("dependencies");
        if (dependenciesNodes.getLength() == 0) {
            return List.of();
        }
        List<RawDependency> dependencies = new ArrayList<>();
        NodeList children = dependenciesNodes.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            if ("group".equals(element.getLocalName())) {
                addDependencyChildren(element, dependencies);
            } else if ("dependency".equals(element.getLocalName())) {
                addDependency(element, dependencies);
            }
        }
        return dependencies;
    }

    private static void addDependencyChildren(Element group, List<RawDependency> dependencies) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependency".equals(((Element) child).getLocalName())) {
                addDependency((Element) child, dependencies);
            }
        }
    }

    private static void addDependency(Element dependency, List<RawDependency> dependencies) {
        String id = dependency.getAttribute("id");
        if (id.isBlank()) {
            return;
        }
        String version = dependency.getAttribute("version");
        dependencies.add(new RawDependency(id, version.isBlank() ? null : version));
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
            document = XmlParser.parseDocument(content);
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
        return extractFromNupkg(new ByteArrayInputStream(nupkgBytes));
    }

    /**
     * {@code extractFromNupkg(byte[])}'s own streamed sibling, for an upload too large to ever
     * hold as one array: {@link ZipInputStream} reads entries sequentially, the same way for a
     * file as for a resident array, so this never needed random/seekable access to begin with.
     */
    static Metadata extractFromNupkg(Path nupkgFile) {
        try (InputStream in = Files.newInputStream(nupkgFile)) {
            return extractFromNupkg(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded package as a .nupkg archive.", e);
        }
    }

    private static Metadata extractFromNupkg(InputStream nupkgStream) {
        try (ZipInputStream zip = new ZipInputStream(nupkgStream)) {
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
