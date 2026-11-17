package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Retains a Cargo crate version from the real public {@code crates.io} registry a repository's
 * {@code public:crates-io} external connection names, the way real CodeArtifact does when a
 * {@code cargo} client requests a crate version that is not present locally yet (confirmed real
 * and documented from AWS's own {@code external-connection.html}: "Rust | Crates.io |
 * public:crates-io"), mirroring {@link CodeArtifactNuGetExternalConnectionImporter}'s own
 * claim/fence/retention shell exactly.
 *
 * <p><b>One thing this format genuinely needs that NuGet/Maven/pypi don't</b>: {@link
 * CodeArtifactNuGetExternalConnectionImporter}'s own javadoc deliberately does not merge the real
 * upstream version listing into any local response, since a NuGet client can fetch a known {@code
 * .nupkg} URL directly without ever consulting a version-list index first. A real {@code cargo}
 * client cannot work that way: {@code cargo build}/{@code cargo add} always resolve against the
 * sparse index first, and the index for one crate name is a single file covering every version,
 * not a per-version URL. Skipping a listing merge the way NuGet does would make an upstream-only
 * crate invisible to Cargo's own resolver even though the download route alone could serve it.
 * {@link #mergeExternalVersions} is that merge: a pure, read-only proxy of the real upstream
 * index's own version lines not already known locally, never a retention write (Kellnr has no
 * "write an index entry without the crate bytes" capability, so there is nothing to write here
 * anyway). It takes no package fence snapshot the way {@code CodeArtifactService
 * #mergeMavenMetadataUnderPackageFence}'s own package-level merge does: that method guards a real,
 * persistent write to Reposilite's own {@code maven-metadata.xml} against a concurrent {@code
 * DeletePackage}/{@code DeleteRepository} resurrecting deleted content; this method never
 * persists anything at all, so the same race's worst case is a single HTTP response momentarily
 * showing a version that is concurrently being deleted, self-correcting on the very next request
 * once the deletion fence moves.
 */
@ApplicationScoped
public class CodeArtifactCargoExternalConnectionImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactCargoExternalConnectionImporter.class);

    private final CodeArtifactService service;
    private final KellnrSidecarClient kellnr;
    private final CargoExternalConnectionClient externalConnectionClient;
    private final ObjectMapper mapper;

    @Inject
    public CodeArtifactCargoExternalConnectionImporter(CodeArtifactService service, KellnrSidecarClient kellnr,
            CargoExternalConnectionClient externalConnectionClient, ObjectMapper mapper) {
        this.service = service;
        this.kellnr = kellnr;
        this.externalConnectionClient = externalConnectionClient;
        this.mapper = mapper;
    }

    /**
     * Attempts to retain {@code packageName}/{@code version}'s real {@code .crate} into {@code
     * repoId} from {@code repository}'s own cargo external connection. Returns {@code true} only
     * when the crate is genuinely present under this connection's claim by the time this returns,
     * whether because this attempt imported it or a concurrent one already had, the same contract
     * {@link CodeArtifactNuGetExternalConnectionImporter#tryImport}'s own javadoc documents.
     */
    public boolean tryImport(String region, String domain, String owner, String repository, String repoId,
            String packageName, String version) {
        String externalConnectionName = cargoExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "cargo", null, packageName,
                "EXTERNAL_UPSTREAM")) {
            return false;
        }

        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "cargo", null, packageName);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "cargo", null, packageName, version);
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "cargo", null, packageName, version, externalConnectionName,
                RetentionOriginKind.EXTERNAL_CONNECTION, packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean imported = false;
        try {
            if (kellnr.packageVersionExists(repoId, domain, repository, null, packageName, version)) {
                imported = service.retainedVersionOriginConnection(region, domain, owner, repository, "cargo", null,
                        packageName, version).filter(externalConnectionName::equals).isPresent();
                return imported;
            }
            Optional<byte[]> index = externalConnectionClient.fetchSparseIndex(externalConnectionName, packageName);
            if (index.isEmpty()) {
                return false;
            }
            Optional<JsonNode> indexLine = findIndexLine(index.get(), version);
            if (indexLine.isEmpty()) {
                return false;
            }
            Path crateFile;
            try {
                crateFile = Files.createTempFile("codeartifact-cargo-import-", ".crate");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create a staging file for a Cargo external-connection "
                        + "import", e);
            }
            try {
                Optional<CargoExternalConnectionClient.FetchedFile> fetched;
                try {
                    fetched = externalConnectionClient.fetchCrate(externalConnectionName, packageName, version,
                            crateFile);
                } catch (AssetDigestingInputStream.AssetTooLargeException e) {
                    LOG.warnv("Skipping {0} {1}: the real registry''s own file is larger than this emulator''s "
                            + "{2}-byte asset quota.", packageName, version,
                            CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES);
                    return false;
                }
                if (fetched.isEmpty()) {
                    return false;
                }
                String expectedSha256 = indexLine.get().path("cksum").asText(null);
                if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(fetched.get().sha256())) {
                    LOG.warnv("Skipping {0} {1}: its real bytes do not match the real index''s own cksum.",
                            packageName, version);
                    return false;
                }
                long contentLength = fetched.get().size();
                Path stagedWireBody = buildStagedWireBody(metadataFromIndexLine(indexLine.get()), crateFile);
                try {
                    CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(
                            region, domain, owner, repository, "cargo", null, packageName, version,
                            versionFenceSnapshot, packageFenceSnapshot, claim.episodeId(), repoId, packageName
                                    + "-" + version + ".crate", contentLength,
                            () -> {
                                KellnrSidecarClient.KellnrPublishResult publishResult = kellnr.publishRawWireBody(
                                        repoId, stagedWireBody, fileSize(stagedWireBody));
                                if (publishResult.statusCode() != 200) {
                                    throw new IllegalStateException("Kellnr refused this retained crate: upstream "
                                            + "returned " + publishResult.statusCode());
                                }
                            });
                    imported = result.fenceMatched() && result.wroteNewContent();
                    return imported;
                } finally {
                    try {
                        Files.deleteIfExists(stagedWireBody);
                    } catch (IOException e) {
                        LOG.warnv(e, "Could not delete a staged Cargo external-connection wire-body file at {0}",
                                stagedWireBody);
                    }
                }
            } finally {
                try {
                    Files.deleteIfExists(crateFile);
                } catch (IOException e) {
                    LOG.warnv(e, "Could not delete a staged Cargo external-connection crate file at {0}", crateFile);
                }
            }
        } finally {
            if (imported) {
                service.ensureRetainedPackageOriginDefaultIfCurrentRepository(region, domain, owner, repository,
                        "cargo", null, packageName, packageFenceSnapshot, repoId);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "cargo", null, packageName,
                    version, claim.episodeId(), imported);
        }
    }

    /**
     * {@code localIndexBytes}'s own real version lines, plus every real upstream-only version
     * line {@code packageName}'s own real {@code crates.io} index carries that isn't already a
     * local line, each upstream line passed through close to verbatim (never re-serialized),
     * matching the "proxy real content verbatim" philosophy {@code CodeArtifactCargoController}'s
     * own local sparse-index route already uses. A {@code BLOCK}ed package, or one with no cargo
     * external connection configured, gets {@code localIndexBytes} back unchanged: "behave exactly
     * as if the external content did not exist," the same rule every other retention path in this
     * codebase already follows.
     */
    public Optional<byte[]> mergeExternalVersions(String region, String domain, String owner, String repository,
            String packageName, Optional<byte[]> localIndexBytes) {
        String externalConnectionName = cargoExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return localIndexBytes;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "cargo", null, packageName,
                "EXTERNAL_UPSTREAM")) {
            return localIndexBytes;
        }
        Optional<byte[]> upstream = externalConnectionClient.fetchSparseIndex(externalConnectionName, packageName);
        if (upstream.isEmpty()) {
            return localIndexBytes;
        }
        List<String> localLines = splitLines(localIndexBytes.orElse(new byte[0]));
        List<String> upstreamLines = splitLines(upstream.get());
        Map<String, String> merged = new LinkedHashMap<>();
        for (String line : localLines) {
            indexLineVersion(line).ifPresent(vers -> merged.put(vers, line));
        }
        for (String line : upstreamLines) {
            indexLineVersion(line).ifPresent(vers -> merged.putIfAbsent(vers, line));
        }
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n", merged.values()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether {@code repository} has a cargo external connection to fetch through. A repository
     * deleted since its name was read, as an upstream in a search order can be, has none.
     */
    boolean hasExternalConnection(String region, String domain, String owner, String repository) {
        try {
            return cargoExternalConnectionName(region, domain, owner, repository) != null;
        } catch (AwsException e) {
            return false;
        }
    }

    Optional<String> indexLineVersion(String line) {
        try {
            return Optional.ofNullable(mapper.readTree(line).path("vers").asText(null));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static List<String> splitLines(byte[] indexBytes) {
        List<String> lines = new ArrayList<>();
        for (String line : new String(indexBytes, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    Optional<JsonNode> findIndexLine(byte[] indexBytes, String version) {
        for (String line : splitLines(indexBytes)) {
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (IOException e) {
                continue;
            }
            if (version.equals(node.path("vers").asText(null))) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /**
     * A real {@code cargo publish} metadata object built from {@code indexLine}'s own real
     * fields, the same honest, bounded limitation {@code KellnrSidecarClient
     * #minimalPublishMetadata} already documents for {@code CopyPackageVersions}: the real
     * index never carries {@code authors}/{@code description}/etc. at all (those live in the
     * {@code .crate}'s own embedded {@code Cargo.toml}, not the index), so those fields stay
     * null/empty here too. Unlike that method, {@code deps}/{@code features} are the crate's own
     * real values, not always empty: the real index already has them, confirmed from a live
     * fetch of {@code index.crates.io}'s own per-crate index file.
     *
     * <p>A renamed dependency's alias and real crate name swap fields between the two shapes
     * (confirmed from the Cargo Book's own registry-index/registry-web-api pages): the index puts
     * the alias in its own {@code name} and the real crate in {@code package}, while publish
     * metadata puts the real crate in {@code name} and the alias in {@code
     * explicit_name_in_toml}; an unrenamed dependency has no {@code package} field at all, and
     * its index {@code name} is already the real crate name. {@code req} is renamed to {@code
     * version_req} going the same way; every other dependency field name is identical in both
     * shapes. {@code features2} (namespaced/weak-dependency feature syntax crates.io splits out
     * for pre-1.19 Cargo compatibility) is merged into {@code features} rather than dropped, the
     * same merge the Cargo Book documents a real client performing on read. Top-level {@code
     * links} is identical in both shapes; top-level {@code rust_version} is carried over even
     * though crates.io itself re-derives it from the {@code .crate}'s own {@code Cargo.toml}
     * rather than trusting the index's copy.
     */
    byte[] metadataFromIndexLine(JsonNode indexLine) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("name", indexLine.path("name").asText());
        metadata.put("vers", indexLine.path("vers").asText());
        ArrayNode deps = metadata.putArray("deps");
        for (JsonNode dep : indexLine.path("deps")) {
            ObjectNode mappedDep = deps.addObject();
            String aliasInIndex = dep.path("name").asText(null);
            String realCrateName = dep.path("package").asText(null);
            if (realCrateName != null) {
                mappedDep.put("name", realCrateName);
                mappedDep.put("explicit_name_in_toml", aliasInIndex);
            } else {
                mappedDep.put("name", aliasInIndex);
                mappedDep.put("explicit_name_in_toml", (String) null);
            }
            mappedDep.put("version_req", dep.path("req").asText(null));
            mappedDep.set("features", dep.path("features"));
            mappedDep.put("optional", dep.path("optional").asBoolean(false));
            mappedDep.put("default_features", dep.path("default_features").asBoolean(true));
            mappedDep.set("target", dep.path("target").isMissingNode() ? mapper.nullNode() : dep.path("target"));
            mappedDep.put("kind", dep.path("kind").asText("normal"));
            mappedDep.put("registry", (String) null);
        }
        ObjectNode features = indexLine.path("features").isObject()
                ? indexLine.path("features").deepCopy() : mapper.createObjectNode();
        if (indexLine.path("features2").isObject()) {
            features.setAll((ObjectNode) indexLine.path("features2"));
        }
        metadata.set("features", features);
        metadata.putArray("authors");
        metadata.put("description", (String) null);
        metadata.put("documentation", (String) null);
        metadata.put("homepage", (String) null);
        metadata.put("readme", (String) null);
        metadata.put("readme_file", (String) null);
        metadata.putArray("keywords");
        metadata.putArray("categories");
        metadata.put("license", (String) null);
        metadata.put("license_file", (String) null);
        metadata.put("repository", (String) null);
        metadata.putObject("badges");
        metadata.put("links", indexLine.path("links").asText(null));
        metadata.put("rust_version", indexLine.path("rust_version").asText(null));
        return metadata.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the real Cargo publish wire body (see {@code KellnrSidecarClient
     * #publishRawWireBody}'s own javadoc for its shape) straight to a staged file rather than one
     * resident {@code byte[]} the way {@code KellnrSidecarClient#publishWireBody} does for {@code
     * CopyPackageVersions}' own always-small, synthesized-empty-deps case: a crate retained
     * through an external connection can be sized anywhere up to the same 5 GB quota any other
     * asset can, so the header is written first and {@code crateFile}'s own real bytes are then
     * streamed straight through rather than ever held alongside it in memory.
     */
    static Path buildStagedWireBody(byte[] metadataJson, Path crateFile) {
        Path staged;
        try {
            staged = Files.createTempFile("codeartifact-cargo-import-wire-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a staging file for a Cargo external-connection "
                    + "publish wire body", e);
        }
        try {
            long crateLength = Files.size(crateFile);
            try (OutputStream out = Files.newOutputStream(staged)) {
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadataJson.length).array());
                out.write(metadataJson);
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crateLength).array());
                Files.copy(crateFile, out);
            }
            return staged;
        } catch (IOException e) {
            // This method's own temp file, not yet handed back to a caller that could clean it up
            // itself: a caller's own finally is scoped to a path it actually received, so a failure
            // here must clean up before rethrowing rather than leaving it to a cleanup block that
            // never starts.
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanupFailure) {
                LOG.warnv(cleanupFailure, "Could not delete a staged Cargo external-connection wire-body file at "
                        + "{0} after a failed write", staged);
            }
            throw new UncheckedIOException("Failed to stage a Cargo external-connection publish wire body", e);
        }
    }

    static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String cargoExternalConnectionName(String region, String domain, String owner, String repository) {
        return service.describeRepository(region, domain, owner, repository).getExternalConnections().stream()
                .filter(ec -> "cargo".equals(ec.getPackageFormat()))
                .map(ExternalConnection::getExternalConnectionName)
                .findFirst()
                .orElse(null);
    }
}
