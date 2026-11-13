package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A real outbound client for the one public pypi repository CodeArtifact's {@code public:pypi}
 * external connection names: {@code https://pypi.org}, confirmed live, not inferred.
 *
 * <p>Unlike NuGet's deterministic {@code {id}/{version}/{filename}} content route, a real PyPI
 * file's actual download URL is not deterministic from its coordinate: {@code
 * files.pythonhosted.org} serves it under an opaque, per-file hash path (confirmed live, e.g.
 * {@code /packages/d3/5b/<hash>/is-even-0.0.0.tar.gz}), so there is no path this client can build
 * on its own the way {@link NuGetExternalConnectionClient} does. {@link #fetchSimpleIndex} is
 * what actually supplies that real URL, one package at a time, read directly off the real
 * registry's own PEP 691 JSON Simple API response ({@code files[].url}); {@link #fetchFile} just
 * follows whatever absolute URL the caller read out of that trusted response, never one supplied
 * by anything outside this client's own fixed, hardcoded {@code public:pypi} base.
 *
 * <p>{@link #fetchSimpleIndex} requests the JSON form of the real Simple API (PEP 691, {@code
 * Accept: application/vnd.pypi.simple.v1+json}) rather than the plain-HTML form every pypi
 * client is guaranteed to support, the same deliberate choice {@link NpmExternalConnectionClient}
 * already makes for npm's own abbreviated packument format: confirmed live against {@code
 * pypi.org} that this content type is served, and real pip itself also prefers it over HTML via
 * the exact same content negotiation when the index advertises it, so this is still the real wire
 * shape a real client uses, not a different endpoint invented for this emulator's own
 * convenience. The JSON form's {@code files} entries carry no explicit version field of their
 * own (confirmed live): {@link CodeArtifactPypiExternalConnectionImporter} resolves a file's
 * version from its filename, the same way a real {@code pip}/{@code pypiserver} installation
 * does, using the response's own top-level {@code versions} array as the candidate list.
 *
 * <p>Every failure mode (an unknown connection name, a real non-200 response, a network error)
 * resolves to {@link Optional#empty()} rather than throwing: a caller here is always deciding
 * whether to retain a package version from an external source, and AWS's own documented behavior
 * for an unreachable external repository is to behave exactly as if the asset were not found,
 * with no special error surfaced to the client.
 */
@ApplicationScoped
public class PypiExternalConnectionClient {

    private static final Logger LOG = Logger.getLogger(PypiExternalConnectionClient.class);

    private static final String SIMPLE_JSON_ACCEPT = "application/vnd.pypi.simple.v1+json";

    private static final Map<String, String> BASE_URLS = Map.of(
            "public:pypi", "https://pypi.org");

    /**
     * Real hosts {@link #fetchFile} trusts for a given connection name beyond that connection's
     * own simple-index host (confirmed live: {@code public:pypi}'s real files are served from
     * {@code files.pythonhosted.org}, a different host than {@code pypi.org} itself). Kept
     * separate from {@link #baseUrls} rather than folded into it, since a connection's index host
     * and its real file host are not the same thing here, unlike every other format's own client
     * in this package.
     */
    private static final Map<String, Set<String>> ADDITIONAL_TRUSTED_FILE_HOSTS = Map.of(
            "public:pypi", Set.of("files.pythonhosted.org"));

    /** Maximum number of redirect hops {@link #fetchFile} will chase manually, matching the JDK's
     * own default cap for {@link HttpClient.Redirect#NORMAL}. */
    private static final int MAX_FILE_REDIRECTS = 5;

    private final HttpClient httpClient;
    private final HttpClient fileHttpClient;
    private final Map<String, String> baseUrls;
    private final Map<String, Set<String>> additionalTrustedFileHosts;

    @Inject
    public PypiExternalConnectionClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), BASE_URLS);
    }

    /** For tests: a fake base-URL map, so the wire contract can be exercised against a local
     * {@code HttpServer} fake instead of the real public registry. */
    PypiExternalConnectionClient(HttpClient httpClient, Map<String, String> baseUrls) {
        this(httpClient, baseUrls, ADDITIONAL_TRUSTED_FILE_HOSTS);
    }

    /** For tests: also overrides {@link #ADDITIONAL_TRUSTED_FILE_HOSTS} directly, so {@link
     * #fetchFile}'s host check can be exercised without relying on real DNS for a second host. */
    PypiExternalConnectionClient(HttpClient httpClient, Map<String, String> baseUrls,
            Map<String, Set<String>> additionalTrustedFileHosts) {
        this.httpClient = httpClient;
        // Deliberately its own client, built with Redirect.NEVER regardless of whatever policy
        // httpClient above was given: isTrustedFileHost below only ever inspects the URI this
        // client is about to request, never one somewhere in the middle of an auto-followed
        // redirect chain. A java.net.http.HttpClient's redirect policy is client-wide, not
        // overridable per-request, so sharing httpClient here (built with Redirect.NORMAL in
        // production, matching every sibling external-connection client) would let a 3xx response
        // from a host that genuinely passed that check silently hand this request to an entirely
        // different, unchecked host before fetchFile ever sees the final response, defeating the
        // host check below. fetchFile instead chases a redirect itself, one hop at a time,
        // re-validating the new location's host before ever requesting it.
        this.fileHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.baseUrls = baseUrls;
        this.additionalTrustedFileHosts = additionalTrustedFileHosts;
    }

    /** One package's real Simple API response, or one real file's bytes, fetched from the real
     * public registry. */
    public record FetchedAsset(byte[] content) {}

    /**
     * Fetches {@code packageName}'s own real PEP 691 JSON Simple API response from the real
     * public registry {@code externalConnectionName} names. {@code packageName} is normalized
     * (PEP 503) before being placed in the request path, matching real pip's own behavior and
     * the real registry's own case/separator-insensitive routing (confirmed live: an
     * un-normalized name still resolves there, but the normalized spelling is what this
     * emulator's own sidecar and controller already standardize on everywhere else).
     */
    public Optional<FetchedAsset> fetchSimpleIndex(String externalConnectionName, String packageName) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return Optional.empty();
        }
        String normalized = PypiserverSidecarClient.normalizePackageName(packageName);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl),
                "/simple/" + SidecarUriUtils.encodeSegment(normalized) + "/");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", SIMPLE_JSON_ACCEPT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? Optional.of(new FetchedAsset(response.body())) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0}''s simple index from external connection {1}: {2}", packageName,
                    externalConnectionName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetches one real file's bytes from {@code fileUrl}, an absolute URL the caller must have
     * read directly off a {@link #fetchSimpleIndex} response already confirmed to have come from
     * {@code externalConnectionName}'s own fixed base: this never accepts or builds a URL of its
     * own, since a real PyPI file's own storage path ({@code files.pythonhosted.org}'s opaque
     * hash directories) cannot be computed from a coordinate the way every other sidecar client
     * here can.
     *
     * <p>Unlike every other sidecar client here, this is the one place in this package that
     * issues a real outbound request to a URL read out of response <i>content</i> rather than
     * built from a fixed, hardcoded base, so {@code externalConnectionName}'s recognition alone
     * is not enough: {@code fileUrl}'s own host must also match either that connection's own
     * simple-index host, or one of its {@link #ADDITIONAL_TRUSTED_FILE_HOSTS}. This never widens
     * to "any host the real registry's response happens to mention," only the specific,
     * confirmed-live file host {@code public:pypi} actually uses, the same fixed-allowlist shape
     * {@link #baseUrls} itself already is.
     *
     * <p>That same host check is re-applied to every redirect hop, not just {@code fileUrl}
     * itself: {@link #fileHttpClient} is built with {@link HttpClient.Redirect#NEVER} specifically
     * so a {@code 3xx} response is handled right here rather than chased transparently inside
     * {@link HttpClient#send}, since an auto-followed redirect would hand the real request to
     * whatever host the response names before this method ever gets a chance to check it.
     */
    public Optional<FetchedAsset> fetchFile(String externalConnectionName, String fileUrl) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean fetched = fetchFile(externalConnectionName, fileUrl, in -> in.transferTo(buffer));
        return fetched ? Optional.of(new FetchedAsset(buffer.toByteArray())) : Optional.empty();
    }

    /** One real file's size, fetched straight to {@code destination} without ever being held whole in memory. */
    public record FetchedFile(long size) {}

    /**
     * {@link #fetchFile(String, String)}'s own streamed sibling, for a file too large to ever
     * hold as one {@code byte[]} (~2 GiB, the JVM array limit): the exact same host/redirect
     * validation, with the confirmed-live final response streamed straight to {@code destination}
     * instead of into a resident array. Capped at {@code
     * CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES} during the copy itself, via {@link
     * AssetDigestingInputStream}, rather than downloaded in full and checked only afterward: the
     * real registry's own file size is otherwise unbounded, so without this an oversized one
     * would still be fully pulled across the network and written to local disk before ever being
     * rejected.
     */
    public Optional<FetchedFile> fetchFile(String externalConnectionName, String fileUrl, Path destination) {
        long[] size = new long[1];
        boolean fetched = fetchFile(externalConnectionName, fileUrl,
                in -> size[0] = AssetDigestingInputStream.copyCapped(in, destination,
                        CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES));
        return fetched ? Optional.of(new FetchedFile(size[0])) : Optional.empty();
    }

    private interface BodyConsumer {
        void accept(InputStream body) throws IOException;
    }

    /**
     * The one real fetch loop both {@link #fetchFile(String, String)} and {@link #fetchFile(String,
     * String, Path)} share: every redirect/host-trust decision lives here exactly once, so neither
     * public overload can drift from the other on the part that actually matters for safety.
     * {@code consumer} only ever runs once, against the confirmed-live, already-host-checked
     * final {@code 200} response's own body, streamed rather than pre-buffered by {@link
     * HttpResponse.BodyHandlers#ofInputStream()}: unlike {@code BodyHandlers.ofFile}, this never
     * risks a redirect hop's own (discarded) body colliding with a destination file a later hop
     * might still need to create fresh. Every hop's own request uses the worst-case download
     * timeout, not a flat 30 seconds: the real file this can fetch is up to 5 GB, and its real
     * size isn't known until the final, non-redirect response itself arrives, so a flat, small
     * timeout would cancel a legitimately large, still-in-progress download on that last hop
     * (the redirect hops ahead of it have no body worth waiting on anyway, so the longer timeout
     * costs them nothing).
     */
    private boolean fetchFile(String externalConnectionName, String fileUrl, BodyConsumer consumer) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(fileUrl);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (int hop = 0; hop <= MAX_FILE_REDIRECTS; hop++) {
            if (!isTrustedFileHost(externalConnectionName, baseUrl, uri)) {
                LOG.warnv("Refusing to fetch {0} for external connection {1}: its host is not a trusted file "
                        + "host for that connection.", uri, externalConnectionName);
                return false;
            }
            HttpResponse<InputStream> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(SidecarUriUtils.maxTransferTimeout())
                        .GET()
                        .build();
                response = fileHttpClient.send(request, BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                LOG.warnv("Could not fetch {0} from external connection {1}: {2}", uri, externalConnectionName,
                        e.getMessage());
                return false;
            }
            if (isRedirect(response.statusCode())) {
                StreamingResponses.closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return false;
                }
                try {
                    uri = uri.resolve(location);
                } catch (IllegalArgumentException e) {
                    return false;
                }
                continue;
            }
            if (response.statusCode() != 200) {
                StreamingResponses.closeQuietly(response.body());
                return false;
            }
            try (InputStream body = response.body()) {
                consumer.accept(body);
                return true;
            } catch (IOException e) {
                LOG.warnv("Could not read {0} from external connection {1}: {2}", uri, externalConnectionName,
                        e.getMessage());
                return false;
            }
        }
        LOG.warnv("Too many redirects fetching {0} from external connection {1}; giving up.", fileUrl,
                externalConnectionName);
        return false;
    }


    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307
                || statusCode == 308;
    }

    /**
     * Whether {@code fileUrl}'s own host is one this client actually trusts for
     * {@code externalConnectionName}: either {@code baseUrl}'s own host (every file this
     * connection's own simple-index host happens to serve directly), or one of this instance's
     * {@code additionalTrustedFileHosts} for that same connection name. A host comparison, not a
     * prefix comparison: a lookalike host such as {@code files.pythonhosted.org.evil.example}
     * must not pass just because it starts with a trusted name.
     *
     * <p>Also requires {@code fileUri}'s own scheme to match {@code baseUrl}'s (raised in
     * review): a host check alone says nothing about the scheme, so a {@code http://} URL, or a
     * redirect hop downgrading to one, naming an otherwise-trusted host would still pass it,
     * sending the real file's bytes in plaintext even though {@code public:pypi}'s own
     * configured base is {@code https}. Compared against {@code baseUrl}'s own scheme rather than
     * a hardcoded {@code https}, the same way the host check already compares against its own
     * host rather than a hardcoded name, so the local {@code HttpServer} fakes this class's own
     * tests run against (plain {@code http}) keep working unchanged.
     */
    private boolean isTrustedFileHost(String externalConnectionName, String baseUrl, URI fileUri) {
        String host = fileUri.getHost();
        if (host == null) {
            return false;
        }
        URI base = URI.create(baseUrl);
        if (!base.getScheme().equalsIgnoreCase(fileUri.getScheme())) {
            return false;
        }
        String baseHost = base.getHost();
        if (host.equalsIgnoreCase(baseHost)) {
            return true;
        }
        return additionalTrustedFileHosts.getOrDefault(externalConnectionName, Set.of()).stream()
                .anyMatch(host::equalsIgnoreCase);
    }
}
