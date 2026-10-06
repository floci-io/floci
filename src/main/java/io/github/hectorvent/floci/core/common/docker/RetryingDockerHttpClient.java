package io.github.hectorvent.floci.core.common.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.transport.DockerHttpClient;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decorates a {@link DockerHttpClient} with the {@link DockerRetry} policy so every docker call
 * travelling over the shared daemon socket survives a transient mid-call drop ({@code Broken
 * pipe}, {@code Connection reset}) without each call site having to wrap itself.
 *
 * <p>The daemon drops connections under fan-out load (an LZA Bootstrap stage firing ~15 CodeBuild
 * actions, a Prepare stage at six live containers), and docker-java surfaces the blip as
 * {@code RuntimeException(IOException)} from inside {@code execute()}, which is exactly where
 * this decorator sits. Guarding call sites one at a time was fixed three separate times
 * ({@code createContainerCmd}, {@code startContainerCmd}, {@code ensureVolume}) while ~60 other
 * docker call sites stayed bare; one guard at the transport boundary covers them all, including
 * every call added later.
 *
 * <p>A request is only retried when replaying it cannot change semantics:
 * <ul>
 *   <li>Its body, if any, must live in {@code bodyBytes()}, a plain {@code byte[]} the transport
 *       re-reads from scratch on every attempt. A request whose body is only available as a
 *       one-shot {@code InputStream} (the tar upload of {@code copyArchiveToContainerCmd}) may
 *       have been partially consumed by the failed attempt and is never replayed.</li>
 *   <li>It must not carry {@code hijackedInput()} (bidirectional attach streams).</li>
 *   <li>Its path must not contain {@code /exec}: {@code POST /exec/{id}/start} re-runs a shell
 *       command whose first run may have executed before the socket died, a worse bug than the
 *       one retrying fixes. The {@code contains} spelling (not {@code startsWith}) also covers
 *       exec-create ({@code POST /containers/{id}/exec}), where a replay would merely leak an
 *       unused exec ID; that conservative breadth costs nothing.</li>
 *   <li>Its method and route must be on the allowlist: {@code GET} and {@code HEAD} always;
 *       {@code DELETE}, whose replay after a lost response answers 404 and every caller tolerates
 *       that; {@code PUT} (archive upload, already limited to a byte-array body above); and
 *       {@code POST} only for a container start or stop (a replay gets 304), a wait, an image pull,
 *       a named volume create (docker returns the existing volume) and a named container create (the
 *       caller adopts the container on a 409). Every other {@code POST} is never replayed: commit
 *       would make a second image, pause/unpause/kill a 409 for a state that already changed,
 *       rename a name conflict, restart a second restart, network create a swallowed 409, network
 *       connect/disconnect a spurious conflict, and an unnamed create a second container. A call
 *       added later gets retries only once someone has reasoned about its replay.</li>
 * </ul>
 *
 * <p>Idempotency of what a replay <em>means</em> stays the call site's job: docker answers a
 * replayed start of an already-running container with HTTP 304 (raised above this transport as
 * {@code NotModifiedException} and treated as success by the caller), and {@code ensureVolume}
 * re-checks existence so a replayed create finds the volume the lost response landed.
 */
public final class RetryingDockerHttpClient implements DockerHttpClient {

    private static final Logger LOG = Logger.getLogger(RetryingDockerHttpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String CONNECTION_HEADER = "Connection";
    static final String CONNECTION_CLOSE = "close";

    /**
     * Mirrors the per-call-site policy this decorator replaces: six attempts at 500ms
     * exponential backoff (capped by {@link DockerRetry#BACKOFF_CAP_MS}) rides out the
     * several-second socket saturation a fan-out build wave causes.
     */
    static final int MAX_ATTEMPTS = 6;
    static final long BACKOFF_MS = 500L;

    private final DockerHttpClient delegate;
    private final int maxAttempts;
    private final long backoffMillis;

    public RetryingDockerHttpClient(DockerHttpClient delegate) {
        this(delegate, MAX_ATTEMPTS, BACKOFF_MS);
    }

    RetryingDockerHttpClient(DockerHttpClient delegate, int maxAttempts, long backoffMillis) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    @Override
    public Response execute(Request request) {
        Request effectiveRequest = withConnectionClose(request);
        if (!isReplayable(effectiveRequest)) {
            return delegate.execute(effectiveRequest);
        }
        return DockerRetry.call(maxAttempts, backoffMillis, () -> delegate.execute(effectiveRequest));
    }

    /**
     * Retires every non-hijacked control-plane connection after its response instead of returning
     * it to docker-java's pool. The upstream Apache transport disables stale-connection validation,
     * so a Podman-closed pooled socket can otherwise be leased again and fail on its next write.
     *
     * <p>This is independent of retry eligibility: one-shot archive uploads and {@code /exec}
     * requests must not be replayed, but their successfully completed connections must not be
     * returned to the pool either. Hijacked requests remain unchanged because docker-java upgrades
     * those connections and manages their lifetime as streams.
     */
    static Request withConnectionClose(Request request) {
        if (request.hijackedInput() != null) {
            return request;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        request.headers().forEach((name, value) -> {
            if (!CONNECTION_HEADER.equalsIgnoreCase(name)) {
                headers.put(name, value);
            }
        });
        headers.put(CONNECTION_HEADER, CONNECTION_CLOSE);

        Request.Builder builder = Request.builder()
                .method(request.method())
                .path(request.path())
                .headers(headers);
        if (request.bodyBytes() != null) {
            builder.bodyBytes(request.bodyBytes());
        } else if (request.body() != null) {
            builder.body(request.body());
        }
        return builder.build();
    }

    /**
     * Whether re-executing {@code request} after a transient failure is semantically safe.
     * {@code bodyBytes()} takes precedence over {@code body()} exactly as it does in the
     * transport itself: when both are set the transport sends the bytes and never touches the
     * stream, so the request replays cleanly.
     */
    static boolean isReplayable(Request request) {
        if (request.hijackedInput() != null) {
            return false;
        }
        if (request.path().contains("/exec")) {
            return false;
        }
        if (request.bodyBytes() == null && request.body() != null) {
            return false;
        }
        return switch (request.method()) {
            case "GET", "HEAD", "DELETE", "PUT" -> true;
            case "POST" -> isReplayablePost(request);
            default -> false;
        };
    }

    /** The {@code POST} routes whose replay after a lost response leaves the same outcome. */
    private static boolean isReplayablePost(Request request) {
        String path = request.path();
        int queryStart = path.indexOf('?');
        String route = queryStart < 0 ? path : path.substring(0, queryStart);
        if (route.endsWith("/containers/create")) {
            String query = queryStart < 0 ? "" : path.substring(queryStart + 1);
            for (String param : query.split("&")) {
                if (param.startsWith("name=")) {
                    return true;
                }
            }
            return false;
        }
        if (route.endsWith("/volumes/create")) {
            return hasVolumeName(request.bodyBytes());
        }
        return route.endsWith("/start") || route.endsWith("/stop") || route.endsWith("/wait")
                || route.endsWith("/images/create");
    }

    /**
     * Whether a volume create names its volume. A replayed named create returns the existing
     * volume; a replayed unnamed one makes a second volume and orphans the first.
     */
    private static boolean hasVolumeName(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        try {
            JsonNode name = JSON.readTree(body).get("Name");
            return name != null && name.isTextual() && !name.asText().isBlank();
        } catch (IOException e) {
            LOG.debugv(e, "Not replaying a volume create whose body is not JSON");
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
