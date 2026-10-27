package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * HTTP client for the shared Reposilite sidecar. A CodeArtifact repository maps to a named
 * Reposilite repository ({@link #ensureRepository}), provisioned lazily via Reposilite's
 * {@code maven} settings domain, which hot-reloads without a restart. Implements
 * {@link RepositorySidecarManager} so {@code CodeArtifactService} can release a deleted
 * repository's Maven storage the same generic way it releases any other format's.
 */
@ApplicationScoped
public class ReposiliteSidecarClient implements RepositorySidecarManager {

    private static final String FORMAT = "maven";
    private static final Logger LOG = Logger.getLogger(ReposiliteSidecarClient.class);
    private static final String MAVEN_SETTINGS_PATH = "/api/settings/domain/maven";
    /**
     * {@link SidecarConcurrency#mapBounded}'s key for every {@code listPackageVersions} call: one
     * shared constant, since {@link ReposiliteSidecarManager} backs every CodeArtifact Maven
     * repository with the same single container, unlike pypiserver's one-container-per-repository
     * shape.
     */
    private static final String SHARED_CONTAINER_KEY = "reposilite";
    private static final int REPOSITORY_READY_POLL_MAX_MS = 3_000;
    private static final int REPOSITORY_READY_POLL_INTERVAL_MS = 50;
    /** Real Maven metadata's own documented {@code lastUpdated} shape: UTC, {@code yyyyMMddHHmmss}. */
    private static final DateTimeFormatter METADATA_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC);
    /**
     * The two checksum extensions a real {@code mvn deploy} always PUTs alongside {@code
     * maven-metadata.xml} itself (confirmed against a real deploy's own request log): SHA-1 and
     * MD5, Maven's two long-standing defaults. SHA-256/SHA-512 are a newer, still-optional addition
     * no client this repository's own integration tests exercise ever sends, so neither is handled
     * by {@link #recordVersionDisposed}'s own checksum upkeep.
     */
    private static final List<String> METADATA_CHECKSUM_EXTENSIONS = List.of("sha1", "md5");

    private final ReposiliteSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    /**
     * Every {@link #ensureRepository} call does a read-modify-write of the *same* single settings
     * document (Reposilite has no per-repository create endpoint, only a full-list replace), so a
     * per-repoId lock is not enough: two different repositories provisioned concurrently can each
     * read the list before the other's write lands, and the second PUT silently drops the first
     * repository from the list (confirmed against a real Reposilite instance). One lock serializes
     * every provisioning call regardless of which repository it is for.
     */
    private final Object provisioningLock = new Object();

    @Inject
    public ReposiliteSidecarClient(ReposiliteSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    ReposiliteSidecarClient(ReposiliteSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    /**
     * {@code publicUrl} is unused: unlike npm's package metadata, Maven's wire protocol has no
     * self-referential URLs to rewrite, so there is nothing here for it to do.
     */
    @Override
    public String ensureReady(String repositoryContainerId, String publicUrl) {
        ensureRepository(repositoryContainerId);
        return manager.ensureReady();
    }

    @Override
    public void release(String repositoryContainerId) {
        releaseRepository(repositoryContainerId);
    }

    /**
     * {@code namespace} is the Maven groupId; the caller is responsible for requiring it, since
     * every real Maven coordinate has one. Builds the standard Maven repository layout path
     * (groupId with dots as slashes, then artifactId, version, and the asset's own filename).
     *
     * <p>Unlike {@link #fetchArtifact(String, String)}, which the real wire-protocol GET route
     * uses with a single caller-supplied path that is legitimately allowed to contain {@code /}
     * (it's the whole relative GAV path), every one of this method's four coordinates is its own
     * independent, untrusted value that is only supposed to be one path segment. Concatenating
     * them with {@code +} the way the wire-protocol route concatenates its one path would let a
     * {@code /} inside, say, {@code assetName} splice in extra path segments, or a value of
     * exactly {@code ..} walk back out of the version directory it's supposed to be confined to.
     * Each coordinate is therefore percent-encoded on its own via
     * {@link SidecarUriUtils#encodeSegment(String)}, which escapes both of those, before being
     * joined with the real {@code /} separators this method supplies itself.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        ensureRepository(repositoryContainerId);
        String baseUrl = manager.ensureReady();
        StringBuilder path = new StringBuilder("/").append(SidecarUriUtils.encodeSegment(repositoryContainerId));
        for (String namespacePart : namespace.split("\\.", -1)) {
            path.append('/').append(SidecarUriUtils.encodeSegment(namespacePart));
        }
        path.append('/').append(SidecarUriUtils.encodeSegment(packageName))
                .append('/').append(SidecarUriUtils.encodeSegment(version))
                .append('/').append(SidecarUriUtils.encodeSegment(assetName));
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), path.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        return Optional.of(response.body());
    }

    /**
     * Only a confirmed 404 means absent; confirmed live (a {@code GET} on a missing directory, or a
     * never-provisioned repository, both answer a clean 404). Anything else, an auth failure or a
     * server error, throws instead of collapsing to "not found": a Reposilite outage must not read
     * as every Maven package having been deleted.
     */
    @Override
    public boolean packageExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                        packageDirectoryPath(repositoryContainerId, namespace, packageName)))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        int status = send(request, BodyHandlers.discarding()).statusCode();
        if (status == 404) {
            return false;
        }
        if (status != 200) {
            throw new IllegalStateException("Could not look up " + packageName + " on the Reposilite sidecar: "
                    + "upstream returned " + status);
        }
        return true;
    }

    /**
     * Removes a whole package, every version at once: a single {@code DELETE} on the group/artifact
     * directory. Confirmed against a live Reposilite container: it recursively removes everything
     * beneath that path in one call. {@link #packageExists} is checked by the caller first, since
     * Reposilite answers a confusing HTTP 500 for a {@code DELETE} on a path that was never there,
     * not a 404.
     */
    @Override
    public void deletePackage(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                        packageDirectoryPath(repositoryContainerId, namespace, packageName)))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .DELETE()
                .build();
        HttpResponse<Void> response = send(request, BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not delete " + packageName
                    + " from the Reposilite sidecar: upstream returned " + response.statusCode());
        }
    }

    private static String packageDirectoryPath(String repositoryContainerId, String namespace, String packageName) {
        StringBuilder path = new StringBuilder("/").append(SidecarUriUtils.encodeSegment(repositoryContainerId));
        for (String namespacePart : namespace.split("\\.", -1)) {
            path.append('/').append(SidecarUriUtils.encodeSegment(namespacePart));
        }
        path.append('/').append(SidecarUriUtils.encodeSegment(packageName)).append('/');
        return path.toString();
    }

    /**
     * Reposilite has no per-GAV metadata beyond a directory listing, so this asks its own details
     * API (the same one {@link #isRepositoryServable} already relies on to distinguish "repository
     * not provisioned yet" from "path genuinely not there") for the version directory itself,
     * rather than any one file inside it: a clean 200 there, directory listing or not, is enough to
     * confirm the version exists, since Reposilite only serves a directory's details for a path it
     * actually has.
     */
    @Override
    public boolean packageVersionExists(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String baseUrl = manager.ensureReady();
        String path = versionDetailsPath(repositoryContainerId, namespace, packageName, version);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        int status = send(request, BodyHandlers.discarding()).statusCode();
        if (status == 404) {
            return false;
        }
        if (status != 200) {
            throw new IllegalStateException("Could not look up " + packageName + " " + version + " on the "
                    + "Reposilite sidecar: upstream returned " + status);
        }
        return true;
    }

    private static String versionDetailsPath(String repositoryContainerId, String namespace, String packageName,
            String version) {
        return "/api/maven/details" + packageDirectoryPath(repositoryContainerId, namespace, packageName)
                + SidecarUriUtils.encodeSegment(version);
    }

    /**
     * Reposilite's own details API, already relied on by {@link #packageVersionExists} to confirm
     * a version directory exists, also lists every entry directly beneath it: this reads that same
     * response's {@code files} array a second way, keeping only the {@code FILE} entries (never a
     * subdirectory, though a plain Maven version directory never has one) and returning their
     * names, for {@code CopyPackageVersions} to enumerate what it needs to fetch and copy without
     * any generic-format {@code packageVersions} record to read an asset list from.
     */
    @Override
    public List<String> listPackageVersionAssetNames(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String baseUrl = manager.ensureReady();
        String path = versionDetailsPath(repositoryContainerId, namespace, packageName, version);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list " + packageName + " " + version + " on the Reposilite "
                    + "sidecar: upstream returned " + response.statusCode());
        }
        JsonNode details = readTree(response.body());
        List<String> names = new ArrayList<>();
        for (JsonNode entry : details.path("files")) {
            if ("FILE".equals(entry.path("type").asText())) {
                names.add(entry.path("name").asText());
            }
        }
        return names;
    }

    /**
     * Enumerates every version directory beneath the artifact itself (the same {@code files}
     * array {@link #listPackageVersionAssetNames} already reads one level down, here kept to its
     * {@code DIRECTORY} entries, inverted from that method's own {@code FILE}-only filter), then
     * asks each version's own details page (again the same endpoint {@link #packageVersionExists}
     * already confirms existence through) for a representative publish time: the latest {@code
     * lastModifiedTime} among its files, since a real {@code mvn deploy} writes the POM and the
     * main artifact as two separate PUTs a moment apart and the later one is the more accurate
     * "when this version became complete." A version directory with no {@code FILE} entries at
     * all (never happens through a real deploy, but not assumed impossible) reports no timestamp
     * rather than guessing one.
     *
     * <p>The per-version details requests run through {@link SidecarConcurrency#mapBounded}, not a
     * plain sequential loop: an artifact with many versions used to make every one of those
     * requests one at a time before returning even a single-result page. Keyed by {@link
     * #SHARED_CONTAINER_KEY} so the concurrency cap holds across simultaneous calls too, since
     * every Maven repository shares the one Reposilite container.
     */
    @Override
    public List<PackageVersionTimestamp> listPackageVersions(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName) {
        String baseUrl = manager.ensureReady();
        String artifactPath = "/api/maven/details" + packageDirectoryPath(repositoryContainerId, namespace,
                packageName);
        HttpRequest artifactRequest = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                        artifactPath))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> artifactResponse = send(artifactRequest, BodyHandlers.ofString());
        if (artifactResponse.statusCode() == 404) {
            return List.of();
        }
        if (artifactResponse.statusCode() != 200) {
            throw new IllegalStateException("Could not list versions of " + packageName + " on the Reposilite "
                    + "sidecar: upstream returned " + artifactResponse.statusCode());
        }
        List<String> versions = new ArrayList<>();
        for (JsonNode entry : readTree(artifactResponse.body()).path("files")) {
            if ("DIRECTORY".equals(entry.path("type").asText())) {
                versions.add(entry.path("name").asText());
            }
        }
        return SidecarConcurrency.mapBounded(SHARED_CONTAINER_KEY, versions, version -> new PackageVersionTimestamp(
                version, latestFileTimestamp(baseUrl, versionDetailsPath(repositoryContainerId, namespace,
                        packageName, version))));
    }

    /**
     * One {@code /api/maven/details} response, split into what {@link #walkMavenDirectory} needs:
     * which file names this directory holds directly, and which entries are subdirectories to
     * possibly recurse into.
     */
    private record MavenDirectoryListing(List<String> fileNames, List<String> subdirectories) {}

    /**
     * Reposilite has no single list-everything endpoint (confirmed live: only the same one-level
     * {@code /api/maven/details} directory listing {@link #listPackageVersionAssetNames}/{@link
     * #listPackageVersions} already read one known directory at a time), so this walks the
     * repository's directory tree from its root, recursing into {@code DIRECTORY} entries.
     *
     * <p>A plain groupId segment can never be told apart from the group/artifact directory itself
     * by its own shape alone: both hold nothing but further {@code DIRECTORY} entries (confirmed
     * live: {@code spike}'s own listing, one {@code DIRECTORY} entry named {@code 1.0.0}, looks
     * identical in shape to {@code com}'s own listing, one {@code DIRECTORY} entry named {@code
     * example}). What tells them apart is one level deeper: a version directory's own children are
     * never further directories, only real files (or, after a {@code DisposePackageVersions}, none
     * at all); a plain groupId segment's children always are.
     *
     * <p>Raised by Greptile's review: a real Maven coordinate layout can have both at the very same
     * directory, since nothing stops one groupId from being a literal prefix of another's own
     * segments (AWS's own worked example shape: {@code com:foo:1.0.0} and {@code com.foo:bar:1.0.0}
     * share the real directory {@code com/foo}, one holding both a version directory {@code 1.0.0}
     * and a further groupId segment {@code bar}). Peeking only one representative child, as an
     * earlier version of this method did, answered only "is the current directory package-shaped
     * or group-shaped", a false either/or that depended on which child happened to be peeked (or on
     * {@code maven-metadata.xml}'s presence short-circuiting before any sibling was ever looked at
     * either), silently dropping whichever of the two coordinates it didn't settle on. {@link
     * #walkMavenDirectory} now checks every child of the current directory, not just one: a
     * version-shaped child (no further directories) proves the current directory is itself a
     * package, recorded at most once regardless of how many such children it has; a group-shaped
     * child is recursed into as a further namespace segment, every time, independent of whether the
     * current directory was already recorded. {@code maven-metadata.xml} still short-circuits
     * straight to a record (covering a package {@link #recordVersionAvailable} was called for that
     * happens to have zero version directories of its own, e.g. a {@code CopyPackageVersions}
     * source whose every version failed), but no longer skips the sibling-child walk below it.
     *
     * <p>Raised by hectorvent's review: every CodeArtifact repository gets a maven sidecar id at
     * creation regardless of whether it is ever actually used through maven, so without checking
     * {@link ReposiliteSidecarManager#isStarted} first, exactly like {@link #releaseRepository}
     * already does for the same reason, a {@code ListPackages} call with no format filter would
     * start the one shared Reposilite container just to walk it for a repository that has never
     * published anything through maven.
     */
    @Override
    public List<PackageIdentifier> listPackages(String repositoryContainerId, String domain, String repository) {
        if (!manager.isStarted()) {
            return List.of();
        }
        String baseUrl = manager.ensureReady();
        List<PackageIdentifier> result = new ArrayList<>();
        walkMavenDirectory(baseUrl, "/api/maven/details/" + SidecarUriUtils.encodeSegment(repositoryContainerId)
                + "/", List.of(), result);
        return result;
    }

    private void walkMavenDirectory(String baseUrl, String detailsPath, List<String> pathSegments,
            List<PackageIdentifier> result) {
        MavenDirectoryListing listing = fetchMavenDirectoryListing(baseUrl, detailsPath);
        if (listing == null) {
            return;
        }
        boolean recordedAsPackage = false;
        if (listing.fileNames().contains("maven-metadata.xml")) {
            recordMavenPackage(pathSegments, result);
            recordedAsPackage = true;
        }
        for (String subdirectory : listing.subdirectories()) {
            String childPath = detailsPath + SidecarUriUtils.encodeSegment(subdirectory) + "/";
            MavenDirectoryListing childListing = fetchMavenDirectoryListing(baseUrl, childPath);
            if (childListing == null) {
                continue;
            }
            // Raised by Greptile's review: a real Maven snapshot version directory also holds its
            // own maven-metadata.xml (listing that version's timestamped builds), alongside its
            // real artifact files (jar, pom, checksums), so "has maven-metadata.xml" alone cannot
            // tell that apart from the artifact-level-metadata-only directory the check below
            // exists for (one with no version directories of its own and, this time, no other real
            // files alongside its metadata.xml either). Only the latter shape, a directory whose
            // files are entirely maven-metadata.xml and its checksum sidecars and nothing else, is
            // excluded from being treated as version-shaped; a snapshot version directory's own
            // real artifact files keep it version-shaped exactly like any other.
            boolean isArtifactMetadataOnly = childListing.fileNames().contains("maven-metadata.xml")
                    && childListing.fileNames().stream().allMatch(name -> name.equals("maven-metadata.xml")
                            || name.startsWith("maven-metadata.xml."));
            if (childListing.subdirectories().isEmpty() && !isArtifactMetadataOnly) {
                // This child holds no further directories of its own (real asset files, or none at
                // all post-dispose) and is not artifact-metadata-only either: it is version-shaped,
                // proving the current directory is (also) the group/artifact level itself,
                // regardless of what its other children look like. An artifact-metadata-only child
                // (an artifact-level directory with zero version directories of its own, see
                // recordVersionAvailable's own javadoc, the exact shape a native PUT straight to
                // that file produces) is never version-shaped, no matter how empty it otherwise
                // looks; falling into the recurse below instead is what lets that child record
                // itself through this same method's own metadata.xml short-circuit above, rather
                // than this directory being wrongly recorded as the package in its place.
                if (!recordedAsPackage) {
                    recordMavenPackage(pathSegments, result);
                    recordedAsPackage = true;
                }
                continue;
            }
            List<String> nextSegments = new ArrayList<>(pathSegments);
            nextSegments.add(subdirectory);
            walkMavenDirectory(baseUrl, childPath, nextSegments, result);
        }
    }

    private void recordMavenPackage(List<String> pathSegments, List<PackageIdentifier> result) {
        if (pathSegments.size() >= 2) {
            String packageName = pathSegments.get(pathSegments.size() - 1);
            String namespace = String.join(".", pathSegments.subList(0, pathSegments.size() - 1));
            result.add(new PackageIdentifier(namespace, packageName));
        }
    }

    private MavenDirectoryListing fetchMavenDirectoryListing(String baseUrl, String detailsPath) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), detailsPath))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list Maven packages on the Reposilite sidecar: upstream "
                    + "returned " + response.statusCode());
        }
        List<String> fileNames = new ArrayList<>();
        List<String> subdirectories = new ArrayList<>();
        for (JsonNode entry : readTree(response.body()).path("files")) {
            String type = entry.path("type").asText();
            String name = entry.path("name").asText();
            if ("FILE".equals(type)) {
                fileNames.add(name);
            } else if ("DIRECTORY".equals(type)) {
                subdirectories.add(name);
            }
        }
        return new MavenDirectoryListing(fileNames, subdirectories);
    }

    private Instant latestFileTimestamp(String baseUrl, String versionDetailsPath) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), versionDetailsPath))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not read publish times for " + versionDetailsPath + " on the "
                    + "Reposilite sidecar: upstream returned " + response.statusCode());
        }
        Instant latest = null;
        for (JsonNode file : readTree(response.body()).path("files")) {
            if (!"FILE".equals(file.path("type").asText()) || !file.has("lastModifiedTime")) {
                continue;
            }
            double epochSeconds = file.path("lastModifiedTime").asDouble();
            Instant timestamp = Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0));
            if (latest == null || timestamp.isAfter(latest)) {
                latest = timestamp;
            }
        }
        return latest;
    }

    /**
     * Writes {@code content} straight to this version's {@code assetName} path, for {@code
     * CopyPackageVersions}'s destination write. Unlike {@link #deployArtifact}, this never
     * short-circuits on an existing byte-identical match and never leaves Reposilite's own {@code
     * redeployment: false} rejection standing in the way of a differing overwrite: the caller
     * ({@code CodeArtifactService#copyContainerBackedPackageVersions}) has already decided, from
     * its own all-or-nothing batch semantics, exactly which versions get overwritten, and has
     * already called {@link #deletePackageVersionAsset} first for any asset this would otherwise
     * conflict with, so there is nothing left here to defer to Reposilite's own native-publish
     * conflict rule for.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        ensureRepository(repositoryContainerId);
        String baseUrl = manager.ensureReady();
        String gav = versionGav(namespace, packageName, version, assetName);
        HttpRequest request = authenticated(baseUrl, repositoryContainerId, gav)
                .PUT(BodyPublishers.ofByteArray(content))
                .build();
        int status = send(request, BodyHandlers.discarding()).statusCode();
        if (status != 200 && status != 201) {
            throw new IllegalStateException("Could not copy " + packageName + " " + version + "'s asset "
                    + assetName + " to the Reposilite sidecar: upstream returned " + status);
        }
    }

    /**
     * Deletes one already-published asset outright, confirmed working for an arbitrarily deep path
     * the same way {@link #deletePackage}'s own group/artifact-directory {@code DELETE} is already
     * confirmed to work for a shallower one: Reposilite applies the same verb to whatever path it is
     * given, file or directory. Only ever called by {@code CopyPackageVersions}'s overwrite path for
     * an asset {@link #listPackageVersionAssetNames} already confirmed exists, so unlike {@link
     * #deletePackage} this never has to special-case a never-existing path.
     */
    @Override
    public void deletePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        String baseUrl = manager.ensureReady();
        String gav = versionGav(namespace, packageName, version, assetName);
        HttpRequest request = authenticated(baseUrl, repositoryContainerId, gav).DELETE().build();
        HttpResponse<Void> response = send(request, BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not delete " + packageName + " " + version + "'s asset "
                    + assetName + " from the Reposilite sidecar: upstream returned " + response.statusCode());
        }
    }

    /**
     * The real Maven repository layout path for one asset of one package version:
     * {@code <groupId-as-slashes>/<artifactId>/<version>/<filename>}, the same shape {@link
     * #deployArtifact} and {@link #fetchArtifact} already take as their own {@code gav} parameter
     * (and the inverse of what {@code CodeArtifactMavenController#parseNamespaceAndPackageName}
     * builds back out of it).
     */
    private static String versionGav(String namespace, String packageName, String version, String assetName) {
        return namespace.replace('.', '/') + "/" + packageName + "/" + version + "/" + assetName;
    }

    /**
     * Merges {@code version} into the group/artifact-level {@code maven-metadata.xml} a real
     * {@code mvn deploy} always also PUTs, separately from the version directory itself: without
     * this, a version {@code CopyPackageVersions} lands straight in the version directory would
     * never show up to a real client resolving "what versions does this artifact have" against
     * the destination, the one thing this bridge's {@link #listPackageVersionAssetNames}/{@link
     * #storePackageVersionAsset} pair, scoped to one version directory each, cannot reach.
     *
     * <p>Confirmed against a live Reposilite container, same as every other wire assumption in
     * this class: unlike a release artifact, a metadata file is not subject to the repository's
     * own {@code redeployment: false} setting, so {@link #deployArtifact} can PUT one with
     * genuinely different content every time without the conflict a real artifact overwrite
     * would hit.
     *
     * <p>{@code latest} is set to {@code version}: real Maven defines it as whichever version was
     * most recently added, and this call existing at all means {@code version} just was. {@code
     * release} only moves to {@code version} when it is not a {@code -SNAPSHOT}; a snapshot copy
     * leaves whatever release version the metadata already named untouched. An existing
     * {@code groupId}/{@code artifactId} already on the file is preserved verbatim rather than
     * re-derived from {@code namespace}/{@code packageName}, since nothing here needs it to be;
     * the already-published metadata is trusted over Floci's own request-path coordinates for
     * these two fields specifically.
     */
    @Override
    public void recordVersionAvailable(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        ensureRepository(repositoryContainerId);
        String gav = artifactMetadataGav(namespace, packageName);
        Optional<FetchedArtifact> existing = fetchArtifact(repositoryContainerId, gav);
        List<String> versions = new ArrayList<>();
        String groupId = namespace;
        String artifactId = packageName;
        String release = null;
        if (existing.isPresent()) {
            String xml = new String(existing.get().content(), StandardCharsets.UTF_8);
            versions.addAll(XmlParser.extractAll(xml, "version"));
            groupId = XmlParser.extractFirst(xml, "groupId", namespace);
            artifactId = XmlParser.extractFirst(xml, "artifactId", packageName);
            release = XmlParser.extractFirst(xml, "release", null);
        }
        if (!versions.contains(version)) {
            versions.add(version);
        }
        if (!version.endsWith("-SNAPSHOT")) {
            release = version;
        }
        XmlBuilder metadata = new XmlBuilder().start("metadata")
                .elem("groupId", groupId)
                .elem("artifactId", artifactId)
                .start("versioning")
                .elem("latest", version)
                .elem("release", release)
                .start("versions");
        for (String v : versions) {
            metadata.elem("version", v);
        }
        metadata.end("versions")
                .elem("lastUpdated", METADATA_TIMESTAMP.format(Instant.now()))
                .end("versioning")
                .end("metadata");
        deployArtifact(repositoryContainerId, gav, metadata.build().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@link #recordVersionAvailable}'s dispose-time counterpart (found missing by Qodo review on
     * the PR that added {@code DisposePackageVersions}): without this, a disposed Maven version's
     * real asset files are gone from its own version directory, but {@code mvn}/{@code gradle}
     * resolving dependencies straight against the artifact-level {@code maven-metadata.xml} (not
     * through this emulator's own {@code ListPackageVersions}, which already reads the override
     * store and correctly stops reporting it) would keep seeing it listed as available. Removes
     * {@code version} from the metadata's own {@code versions} list and recomputes {@code latest}/
     * {@code release} from whatever remains, in the same append order the metadata already
     * preserves: the list is never re-sorted, so its last surviving entry is still the most
     * recently deployed one, exactly the same invariant {@link #recordVersionAvailable} relies on
     * when adding to it. A metadata file that would end up with no versions left is deleted
     * outright rather than written back empty, matching how a package that never had a version
     * published has no metadata file at all. A missing metadata file, or one that never named this
     * version to begin with (both possible if this is called more than once, or for a version
     * recorded before {@code maven-metadata.xml} existed), is a no-op either way.
     *
     * <p>Raised by Greptile's review: both the DELETE below (when no version is left to list) and
     * the rewrite ({@link #deployArtifact}) ignored whatever Reposilite actually answered, so a
     * disposal could report success while the metadata on disk still named the just-deleted
     * version. Checked against the exact same {@code IllegalStateException}-on-failure convention
     * {@link #deletePackageVersionAsset} already uses for the asset deletes this runs after (see
     * {@code CodeArtifactService#deleteContainerBackedVersionAssets}'s own javadoc for why this
     * runs last): a thrown exception here propagates the same way a failed asset delete already
     * does, rather than this method silently reporting a metadata update that never actually took.
     *
     * <p>Also raised by Greptile's review: a real {@code mvn deploy} always PUTs {@code
     * maven-metadata.xml.sha1}/{@code .md5} right alongside the metadata file itself, so this
     * method's own server-side rewrite (or removal) of the metadata content must carry those two
     * checksum siblings along with it ({@link #METADATA_CHECKSUM_EXTENSIONS}): left untouched, a
     * Maven client verifying the checksum of the metadata it just fetched would see it disagree
     * with content this method itself just changed underneath it.
     *
     * <p>Raised again by Greptile's review, once a dispose retry (see {@code
     * CodeArtifactService#checkStatusTransitionAllowed}'s own javadoc) became possible: a retry
     * whose first attempt already finished the metadata rewrite or removal, but then failed on
     * exactly a checksum write or delete, used to hit one of the two early returns below and
     * report success without ever repairing that stale checksum, since neither {@code existing}
     * being empty nor {@code version} being already absent from it means the checksums are
     * actually right. Both early-return branches now repair the checksums too, every time, not
     * just on the one call that happened to also touch the metadata itself.
     *
     * <p>Raised again by Greptile's review: {@link #fetchArtifact} itself returns {@code empty()}
     * for every non-200 response, 500/503 included, the same conflation {@link
     * #deleteArtifactIfPresent}'s own existence check already had fixed for it. A transient read
     * failure here looked identical to "the metadata file genuinely does not exist," which would
     * delete the checksums and report a successful disposal while the metadata on disk still
     * named the version this was supposed to remove. Reads the metadata through a dedicated check
     * that only takes the empty branch on a confirmed 404, throwing on anything else, the same
     * convention {@link #deleteArtifactIfPresent}'s own HEAD check already uses.
     *
     * <p>Known, documented gap left out of scope (also raised by Greptile's review): this
     * method's own read-modify-write of {@code maven-metadata.xml} has no lock around it, so a
     * concurrent {@link #recordVersionAvailable} (a native {@code mvn deploy} adding a different
     * version to the same artifact) that reads the file between this method's own read and write
     * can have its update silently overwritten by this call's stale copy, or removed outright if
     * this call's own last-version branch deletes the file instead. This is not new: {@link
     * #recordVersionAvailable} already has the identical unlocked read-modify-write shape on its
     * own, so two concurrent deploys of different versions to the same artifact can already lose
     * one's update today, with no disposal involved at all. This method is simply a second
     * caller that inherits that same pre-existing hole rather than one that introduces it.
     * Coordinating every writer of this one file (a lock keyed by repository and artifact,
     * shared across every caller) is a real fix, just not one scoped to adding {@code
     * DisposePackageVersions} itself; tracked separately rather than folded in here.
     */
    @Override
    public void recordVersionDisposed(String repositoryContainerId, String namespace, String packageName,
            String version) {
        String gav = artifactMetadataGav(namespace, packageName);
        Optional<FetchedArtifact> existing = fetchArtifactConfirmingMissing(repositoryContainerId, gav);
        if (existing.isEmpty()) {
            for (String extension : METADATA_CHECKSUM_EXTENSIONS) {
                deleteArtifactIfPresent(repositoryContainerId, gav + "." + extension);
            }
            return;
        }
        String xml = new String(existing.get().content(), StandardCharsets.UTF_8);
        List<String> versions = new ArrayList<>(XmlParser.extractAll(xml, "version"));
        if (!versions.remove(version)) {
            writeMetadataChecksum(repositoryContainerId, gav + ".sha1", Digests.sha1Hex(existing.get().content()));
            writeMetadataChecksum(repositoryContainerId, gav + ".md5", Digests.md5Hex(existing.get().content()));
            return;
        }
        String baseUrl = manager.ensureReady();
        if (versions.isEmpty()) {
            HttpRequest request = authenticated(baseUrl, repositoryContainerId, gav).DELETE().build();
            HttpResponse<Void> response = send(request, BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Could not remove " + packageName + "'s maven-metadata.xml from "
                        + "the Reposilite sidecar after disposing its last version " + version
                        + ": upstream returned " + response.statusCode());
            }
            for (String extension : METADATA_CHECKSUM_EXTENSIONS) {
                deleteArtifactIfPresent(repositoryContainerId, gav + "." + extension);
            }
            return;
        }
        String groupId = XmlParser.extractFirst(xml, "groupId", namespace);
        String artifactId = XmlParser.extractFirst(xml, "artifactId", packageName);
        String latest = versions.get(versions.size() - 1);
        String release = null;
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (!versions.get(i).endsWith("-SNAPSHOT")) {
                release = versions.get(i);
                break;
            }
        }
        XmlBuilder metadata = new XmlBuilder().start("metadata")
                .elem("groupId", groupId)
                .elem("artifactId", artifactId)
                .start("versioning")
                .elem("latest", latest)
                .elem("release", release)
                .start("versions");
        for (String v : versions) {
            metadata.elem("version", v);
        }
        metadata.end("versions")
                .elem("lastUpdated", METADATA_TIMESTAMP.format(Instant.now()))
                .end("versioning")
                .end("metadata");
        byte[] metadataBytes = metadata.build().getBytes(StandardCharsets.UTF_8);
        DeployOutcome outcome = deployArtifact(repositoryContainerId, gav, metadataBytes);
        if (!outcome.wroteNewContent()) {
            throw new IllegalStateException("Could not update " + packageName + "'s maven-metadata.xml on the "
                    + "Reposilite sidecar after disposing version " + version + ": upstream returned "
                    + outcome.status());
        }
        writeMetadataChecksum(repositoryContainerId, gav + ".sha1", Digests.sha1Hex(metadataBytes));
        writeMetadataChecksum(repositoryContainerId, gav + ".md5", Digests.md5Hex(metadataBytes));
    }

    /** {@link #recordVersionDisposed}'s own checksum-rewrite counterpart to {@link #deployArtifact}. */
    /**
     * Raised by Greptile's review: {@code wroteNewContent()} is {@code false} both for a genuine
     * failure and for {@link #deployArtifact}'s own idempotent short-circuit (the checksum
     * already on the sidecar matches {@code hex} byte for byte, so nothing needed to be sent at
     * all), and checking it alone could not tell the two apart. A disposal retry whose {@code
     * .sha1} write already landed on an earlier attempt, but then failed on {@code .md5}, would
     * see that already-correct {@code .sha1} hit this exact short-circuit every time, getting
     * flagged as a failure that stopped the retry before it ever reached {@code .md5} again. Only
     * the real HTTP status is checked now, the same success codes {@link #deployArtifact} itself
     * already treats as success.
     */
    private void writeMetadataChecksum(String repositoryContainerId, String checksumGav, String hex) {
        DeployOutcome outcome = deployArtifact(repositoryContainerId, checksumGav,
                hex.getBytes(StandardCharsets.UTF_8));
        if (outcome.status() != 200 && outcome.status() != 201) {
            throw new IllegalStateException("Could not update checksum " + checksumGav + " on the Reposilite "
                    + "sidecar: upstream returned " + outcome.status());
        }
    }

    /**
     * {@link #recordVersionDisposed}'s own checksum-removal counterpart, for the case where the
     * metadata file itself is removed outright rather than rewritten. A checksum that was never
     * there to begin with (a client that only ever pushed one of the two, or a sidecar restored
     * from a snapshot older than this method) is a no-op, the same tolerance {@link
     * #recordVersionDisposed} already extends to a metadata file missing version to begin with.
     *
     * <p>Checks existence with a HEAD first rather than guessing at a "not found" status for the
     * DELETE itself: confirmed against a real Reposilite instance, deleting a path that was never
     * there returns 500, not 404, so treating 404 as the tolerated case (as an earlier version of
     * this method did) still threw on the common case of a checksum a real {@code mvn deploy}
     * simply never pushed.
     *
     * <p>Raised by Greptile's review: {@link #artifactExists} itself treats every non-200 HEAD
     * response as "absent," 500/503 included, which would let a genuine sidecar failure here look
     * identical to a confirmed-missing checksum and silently skip the delete instead of surfacing
     * it. This checks the HEAD status directly instead, so only a confirmed 404 is tolerated as
     * absent; anything else (a real error, not a clean answer either way) throws, the same
     * {@code IllegalStateException}-on-failure convention this method's own DELETE already uses,
     * so a caller can tell a skipped-because-confirmed-gone checksum apart from one this could not
     * even check.
     */
    private void deleteArtifactIfPresent(String repositoryContainerId, String gav) {
        String baseUrl = manager.ensureReady();
        HttpRequest headRequest = authenticated(baseUrl, repositoryContainerId, gav)
                .method("HEAD", BodyPublishers.noBody()).build();
        int headStatus = send(headRequest, BodyHandlers.discarding()).statusCode();
        if (headStatus == 404) {
            return;
        }
        if (headStatus != 200) {
            throw new IllegalStateException("Could not check whether " + gav + " exists on the Reposilite "
                    + "sidecar before deleting it: upstream returned " + headStatus);
        }
        HttpRequest request = authenticated(baseUrl, repositoryContainerId, gav).DELETE().build();
        HttpResponse<Void> response = send(request, BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not delete " + gav + " from the Reposilite sidecar: upstream "
                    + "returned " + response.statusCode());
        }
    }

    private static String artifactMetadataGav(String namespace, String packageName) {
        return namespace.replace('.', '/') + "/" + packageName + "/maven-metadata.xml";
    }

    /** Ensures a Reposilite repository named {@code repoId} exists, creating it if not. */
    public void ensureRepository(String repoId) {
        String baseUrl = manager.ensureReady();
        synchronized (provisioningLock) {
            ArrayNode repositories = currentRepositories(baseUrl);
            for (JsonNode repository : repositories) {
                if (repoId.equals(repository.path("id").asText())) {
                    return;
                }
            }
            ObjectNode newRepository = mapper.createObjectNode();
            newRepository.put("id", repoId);
            newRepository.put("visibility", "PUBLIC");
            newRepository.put("redeployment", false);
            repositories.add(newRepository);
            putMavenSettings(baseUrl, repositories);
            waitUntilRepositoryIsServable(baseUrl, repoId);
            LOG.infov("Provisioned Reposilite repository {0}", repoId);
        }
    }

    /**
     * Reposilite's settings PUT returning 200 only means the shared-configuration document was
     * written; it does not mean the repository actually instantiated. A repository whose settings
     * are individually valid but that {@code RepositoryFactory} rejects for a reason the settings
     * endpoint itself never validates (an id over Reposilite's own 64-character limit was one real
     * case here) is silently dropped, logged as an error inside the container, and every later
     * deploy to it 404s with a confusing "Repository not found" that gives no hint why. Polling a
     * side-effect-free read endpoint here turns that into an immediate, clear failure at
     * provisioning time instead.
     */
    private void waitUntilRepositoryIsServable(String baseUrl, String repoId) {
        long deadline = System.currentTimeMillis() + REPOSITORY_READY_POLL_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isRepositoryServable(baseUrl, repoId)) {
                return;
            }
            try {
                Thread.sleep(REPOSITORY_READY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Reposilite repository " + repoId
                        + " to become servable", e);
            }
        }
        throw new IllegalStateException("Reposilite repository " + repoId + " did not become servable within "
                + REPOSITORY_READY_POLL_MAX_MS + " ms");
    }

    /**
     * {@code /api/maven/details/{repository}/{gav}} answers "File not found" for a path missing
     * from a repository Reposilite actually knows about, and "Repository ... not found" for one it
     * doesn't yet, which is exactly the distinction needed here; a probe path that can never be a
     * real artifact makes the "found" case impossible, so only the message text is ever compared.
     */
    private boolean isRepositoryServable(String baseUrl, String repoId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/maven/details/" + repoId + "/.floci-repository-ready-probe"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return true;
        }
        String message = readTree(response.body()).path("message").asText("");
        return !message.startsWith("Repository ");
    }

    /**
     * Deletes every file {@code repoId} holds, then removes it from the shared settings list.
     * Reposilite has no bulk-delete endpoint (checked against its documented REST API); DELETE on
     * a path recursively removes everything under it in one call, though, so this only needs one
     * DELETE per top-level entry (a repository's group-id first segments, typically a handful)
     * rather than walking the whole artifact tree. A repository never used through Maven has
     * nothing registered on the Reposilite side at all; that is a no-op here, not an error.
     *
     * <p>Order matters: the repository must still be registered while its files are deleted,
     * since Reposilite 404s every path, including DELETE, for a repository ID absent from its
     * settings (confirmed against a live instance). Removing it from settings first would leave
     * its files permanently unreachable instead of actually freeing the storage.
     *
     * <p>Checks {@link ReposiliteSidecarManager#isStarted()} first: every CodeArtifact repository
     * gets a Maven sidecar id at creation regardless of whether it is ever actually used through
     * Maven, so without this check, deleting any repository at all would start the shared
     * container just to look for content that was never there. Reposilite keeps no volume, so a
     * sidecar that was never started could not possibly hold anything for {@code repoId} to
     * release.
     */
    public void releaseRepository(String repoId) {
        if (!manager.isStarted()) {
            return;
        }
        String baseUrl = manager.ensureReady();
        synchronized (provisioningLock) {
            for (JsonNode entry : topLevelEntries(baseUrl, repoId)) {
                deleteEntry(baseUrl, repoId, entry.path("name").asText());
            }
            ArrayNode repositories = currentRepositories(baseUrl);
            ArrayNode remaining = mapper.createArrayNode();
            for (JsonNode repository : repositories) {
                if (!repoId.equals(repository.path("id").asText())) {
                    remaining.add(repository);
                }
            }
            if (remaining.size() != repositories.size()) {
                putMavenSettings(baseUrl, remaining);
                LOG.infov("Released Reposilite repository {0}", repoId);
            }
        }
    }

    private ArrayNode topLevelEntries(String baseUrl, String repoId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/maven/details/" + repoId))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // Never provisioned (no Maven use before this CodeArtifact repository was deleted):
            // nothing on the Reposilite side to clean up.
            return mapper.createArrayNode();
        }
        if (response.statusCode() != 200) {
            // Anything other than a clean 404 means the repository's actual state here is
            // unknown, not "nothing to clean up": proceeding to remove the settings entry anyway
            // would orphan whatever this call never got to see, unreachable through the API
            // afterward the same way the 404-ordering bug this method exists to avoid would.
            // Propagating lets the caller's best-effort handling decide, rather than silently
            // treating an error as an empty repository.
            throw new IllegalStateException("Failed to list " + repoId + " on Reposilite before release: HTTP "
                    + response.statusCode());
        }
        JsonNode details = readTree(response.body());
        return details.path("files").isArray() ? ((ArrayNode) details.path("files")).deepCopy()
                : mapper.createArrayNode();
    }

    private void deleteEntry(String baseUrl, String repoId, String entryName) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + repoId + "/" + entryName))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .DELETE()
                .build();
        HttpResponse<Void> response = send(request, BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to delete " + repoId + "/" + entryName
                    + " from Reposilite: HTTP " + response.statusCode());
        }
    }

    /**
     * {@code wroteNewContent} is {@code false} for the idempotent-match short-circuit (nothing was
     * actually sent to Reposilite) and {@code true} only when the real {@code PUT} below got back
     * a success status; a genuine conflict (Reposilite's own {@code redeployment: false} rejection)
     * or any other non-2xx status wrote nothing either, despite having been attempted.
     */
    public record DeployOutcome(int status, boolean wroteNewContent) {}

    /**
     * Deploys {@code content} to {@code repoId}'s {@code gav} path, returning the HTTP status.
     *
     * <p>Reposilite's own {@code redeployment: false} setting (set in {@link #ensureRepository})
     * rejects a redeploy to an existing path unconditionally, even when the new content is
     * byte-identical to what is already there (confirmed against a real instance). AWS's own
     * CodeArtifact docs ("Overwriting package assets" in the packages-overview page) say a
     * republish of an asset whose content matches what is already published succeeds because the
     * operation is idempotent; only a genuine content mismatch is a real conflict. Reposilite has
     * no content-aware mode of its own, so this checks the existing artifact first and
     * short-circuits to a 200 on an exact match rather than ever sending Reposilite a PUT it would
     * reject regardless of whether the retry was actually harmless. That check is best-effort: any
     * failure reading the existing artifact falls through to the real PUT below rather than failing
     * the deploy outright, since Reposilite's own redeployment check is still there to catch a
     * genuine conflict either way.
     */
    public DeployOutcome deployArtifact(String repoId, String gav, byte[] content) {
        String baseUrl = manager.ensureReady();
        if (existingArtifactMatches(baseUrl, repoId, gav, content)) {
            return new DeployOutcome(200, false);
        }
        HttpRequest request = authenticated(baseUrl, repoId, gav)
                .PUT(BodyPublishers.ofByteArray(content))
                .build();
        int status = send(request, BodyHandlers.discarding()).statusCode();
        return new DeployOutcome(status, status == 200 || status == 201);
    }

    /**
     * {@code true} when {@code repoId}'s {@code gav} path already holds content identical to
     * {@code content}. Streams the existing artifact in fixed-size chunks rather than buffering it
     * whole: {@code content} is already fully resident (RESTEasy Reactive buffers the whole request
     * body before {@link CodeArtifactMavenController#deploy} ever runs), so comparing this way
     * costs no second full copy for a large artifact the way downloading it to compare would.
     *
     * <p>Any failure reading the existing artifact, including one genuinely unexpected (a timeout,
     * a dropped connection), reads the same as "can't confirm a match" rather than propagating: a
     * client's upload must never fail just because this best-effort preflight did, when the real
     * PUT below is always there as the safe fallback.
     */
    private boolean existingArtifactMatches(String baseUrl, String repoId, String gav, byte[] content) {
        HttpRequest request = authenticated(baseUrl, repoId, gav).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                return false;
            }
            try (InputStream in = response.body()) {
                byte[] buffer = new byte[8192];
                int offset = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (offset + read > content.length
                            || !Arrays.equals(buffer, 0, read, content, offset, offset + read)) {
                        return false;
                    }
                    offset += read;
                }
                return offset == content.length;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.warnv(e, "Could not check {0}/{1} for an existing match before deploying: {2}", repoId, gav,
                    e.getMessage());
            return false;
        }
    }

    /** Fetches {@code repoId}'s {@code gav} path, or {@link Optional#empty()} on a non-200 response. */
    public Optional<FetchedArtifact> fetchArtifact(String repoId, String gav) {
        return fetchArtifact(manager.ensureReady(), repoId, gav);
    }

    /**
     * {@link #recordVersionDisposed}'s own counterpart to {@link #fetchArtifact}: that method's
     * {@code empty()} is not safe to read as "confirmed absent" everywhere, since it also means
     * "any non-200," 500/503 included. This only takes the empty branch on an actual 404,
     * throwing on anything else so a transient read failure is surfaced rather than treated as
     * the file never having existed.
     */
    private Optional<FetchedArtifact> fetchArtifactConfirmingMissing(String repositoryContainerId, String gav) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = authenticated(baseUrl, repositoryContainerId, gav).GET().build();
        HttpResponse<byte[]> response = send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not read " + gav + " from the Reposilite sidecar: upstream "
                    + "returned " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return Optional.of(new FetchedArtifact(response.body(), contentType));
    }

    private Optional<FetchedArtifact> fetchArtifact(String baseUrl, String repoId, String gav) {
        HttpRequest request = authenticated(baseUrl, repoId, gav).GET().build();
        HttpResponse<byte[]> response = send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return Optional.of(new FetchedArtifact(response.body(), contentType));
    }

    public record FetchedArtifact(byte[] content, String contentType) {}

    /** {@code true} if {@code repoId}'s {@code gav} path exists. */
    public boolean artifactExists(String repoId, String gav) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = authenticated(baseUrl, repoId, gav).method("HEAD", BodyPublishers.noBody()).build();
        return send(request, BodyHandlers.discarding()).statusCode() == 200;
    }

    private ArrayNode currentRepositories(String baseUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MAVEN_SETTINGS_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to read Reposilite maven settings: HTTP "
                    + response.statusCode());
        }
        JsonNode settings = readTree(response.body());
        return settings.path("repositories").isArray()
                ? ((ArrayNode) settings.path("repositories")).deepCopy()
                : mapper.createArrayNode();
    }

    private void putMavenSettings(String baseUrl, ArrayNode repositories) {
        ObjectNode settings = mapper.createObjectNode();
        settings.set("repositories", repositories);
        String payload = writeValue(settings);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MAVEN_SETTINGS_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", manager.basicAuthHeader())
                .header("Content-Type", "application/json")
                .PUT(BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to update Reposilite maven settings: HTTP "
                    + response.statusCode() + " " + response.body());
        }
    }

    /**
     * {@code gav} isn't necessarily a trusted constant: a caller-supplied asset name can reach
     * here (via {@link #fetchPackageVersionAsset}'s bridge from {@code GetPackageVersionAsset}, or
     * the raw wire-protocol GAV path segment itself), so building the URI with a plain string
     * concatenation would let a {@code #} truncate the path at a URI fragment, or a {@code ?}
     * reinterpret the rest as a query string, silently fetching a different, unintended path
     * instead of the one actually requested. The three-argument {@link URI} constructor percent-
     * encodes whatever isn't already valid inside a path while leaving the real {@code /}
     * separators alone, unlike {@link URI#create}.
     */
    private HttpRequest.Builder authenticated(String baseUrl, String repoId, String gav) {
        return HttpRequest.newBuilder()
                .uri(requestUri(baseUrl, "/" + repoId + "/" + gav))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader());
    }

    /**
     * {@code baseUrl} isn't always just scheme and authority: {@code FLOCI_SERVICES_CODEARTIFACT_
     * MAVEN_URL} can point at a pre-configured Reposilite instance behind its own path prefix, so
     * that prefix is carried over, and carried over with whatever encoding it was configured with
     * untouched, rather than dropped or re-encoded. {@code path} can separately carry
     * caller-supplied text (a package or asset name), so it's not safe to hand straight to
     * {@link URI#create} either: a raw string concatenation would let a {@code #} in that text
     * truncate the request at a URI fragment, or a {@code ?} reinterpret the rest as a query
     * string, silently fetching a different path than the one actually requested.
     */
    private static URI requestUri(String baseUrl, String path) {
        try {
            String encodedPath = new URI(null, null, path, null, null).getRawPath();
            return SidecarUriUtils.combine(URI.create(baseUrl), encodedPath);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not build a request URI for " + path, e);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Reposilite sidecar", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Reposilite sidecar: " + safeMessage(e), e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Reposilite maven settings response was not valid JSON", e);
        }
    }

    private String writeValue(ObjectNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Reposilite maven settings", e);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
