package io.github.hectorvent.floci.services.codeartifact;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link CodeArtifactPypiController} against a fake pypiserver backend (a real
 * {@link HttpServer}, not a mock of the JDK {@link HttpClient}, since that client's request/response
 * types are effectively unmockable) so the proxy-passthrough, auth-resolution, and pre-upload
 * overwrite-rejection logic all run for real. {@link CodeArtifactService} and
 * {@link PypiserverSidecarClient} are mocked: what they do is already covered by
 * {@code CodeArtifactServiceTest} and {@code PypiserverSidecarManagerTest}, what matters here is how
 * this controller uses their results.
 */
class CodeArtifactPypiControllerTest {

    private static final String DOMAIN = "dom";
    private static final String REPOSITORY = "repo";
    private static final String REGION = "us-east-1";
    private static final String OWNER = "000000000000";

    private HttpServer backend;
    private String backendUrl;
    private CodeArtifactService service;
    private PypiserverSidecarClient pypiserver;
    private CodeArtifactPypiController controller;

    @BeforeEach
    void setUp() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.start();
        backendUrl = "http://127.0.0.1:" + backend.getAddress().getPort();

        service = mock(CodeArtifactService.class);
        pypiserver = mock(PypiserverSidecarClient.class);
        when(service.resolveAuthorizationToken(anyString(), eq(DOMAIN)))
                .thenReturn(Optional.of(new AuthorizationTokenScope(OWNER, REGION)));
        when(service.ensureFormatContainerId(eq("pypi"), eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY)))
                .thenReturn("pypi-repo-id");
        when(pypiserver.ensureReady(eq("pypi-repo-id"), any())).thenReturn(backendUrl);

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        controller = new CodeArtifactPypiController(service, pypiserver, httpClient);
    }

    @AfterEach
    void tearDown() {
        backend.stop(0);
    }

    @Test
    void requireBaseUrlRejectsAMissingOrUnresolvableToken() {
        when(service.resolveAuthorizationToken(anyString(), eq(DOMAIN))).thenReturn(Optional.empty());

        assertThrows(NotAuthorizedException.class,
                () -> controller.packageIndex(headers("Bearer bogus"), DOMAIN, REPOSITORY, "my-pkg"));
    }

    @Test
    void requireBaseUrlTranslatesAMissingRepositoryIntoNotFound() {
        when(service.ensureFormatContainerId(eq("pypi"), eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY)))
                .thenThrow(new AwsException("ResourceNotFoundException", "no such repository", 404));

        assertThrows(NotFoundException.class,
                () -> controller.packageIndex(headers("Bearer good"), DOMAIN, REPOSITORY, "my-pkg"));
    }

    @Test
    void packageIndexRewritesRootRelativePackageLinksToThisRepositorysPathPrefix() {
        // Real pypiserver response shape, confirmed against a live container: a root-relative
        // href, not the bare filename a naive fixture might use.
        backend.createContext("/simple/my-pkg/", exchange -> respond(exchange, 200, "text/html",
                "<a href=\"/packages/my-pkg-1.0.tar.gz#sha256=abc\">my-pkg-1.0.tar.gz</a>"));

        Response response = controller.packageIndex(headers("Bearer good"), DOMAIN, REPOSITORY, "my-pkg");

        assertEquals(200, response.getStatus());
        String body = new String((byte[]) response.getEntity(), StandardCharsets.UTF_8);
        assertTrue(body.contains("href=\"/codeartifact/pypi/" + DOMAIN + "/" + REPOSITORY
                + "/packages/my-pkg-1.0.tar.gz#sha256=abc\""), body);
    }

    @Test
    void downloadFileProxiesRawBytesFromTheBackend() {
        byte[] content = "package bytes".getBytes(StandardCharsets.UTF_8);
        backend.createContext("/packages/my-pkg-1.0.tar.gz",
                exchange -> respond(exchange, 200, "application/octet-stream", content));

        Response response = controller.downloadFile(headers("Bearer good"), DOMAIN, REPOSITORY, "my-pkg-1.0.tar.gz");

        assertEquals(200, response.getStatus());
        assertEquals("package bytes", new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
    }

    @Test
    void downloadFileReturns404WhenTheBackendHasNoSuchFile() {
        backend.createContext("/packages/missing.tar.gz", exchange -> respond(exchange, 404, "text/plain", ""));

        Response response = controller.downloadFile(headers("Bearer good"), DOMAIN, REPOSITORY, "missing.tar.gz");

        assertEquals(404, response.getStatus());
    }

    /**
     * The fix for a real finding: building the request URI with a plain string concatenation
     * would let a {@code #} in a caller-supplied filename get interpreted as a URI fragment and
     * silently dropped from the actual wire request, fetching a different, shorter real filename
     * instead of the one actually requested. Checks the raw path the backend actually receives,
     * not just the response, via a root fallback context rather than registering the context at
     * the encoded literal path: {@code com.sun.net.httpserver.HttpServer} matches contexts on the
     * decoded path, so registering one at the raw percent-encoded string wouldn't reliably match.
     */
    @Test
    void downloadFileTreatsAHashInTheFilenameAsLiteralNotAUriFragment() {
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 404, "text/plain", "");
        });

        Response response = controller.downloadFile(headers("Bearer good"), DOMAIN, REPOSITORY, "pkg-1.0.jar#forged");

        assertEquals(404, response.getStatus());
        assertEquals("/packages/pkg-1.0.jar%23forged", observedRawPath.get());
    }

    /**
     * Same finding, the path-separator half of it: a {@code /} in a caller-supplied filename
     * would be indistinguishable from a real path separator and could splice in extra path
     * segments.
     */
    @Test
    void downloadFileTreatsASlashInTheFilenameAsLiteralNotAStructuralSeparator() {
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 404, "text/plain", "");
        });

        Response response = controller.downloadFile(headers("Bearer good"), DOMAIN, REPOSITORY, "../secret.tar.gz");

        assertEquals(404, response.getStatus());
        assertEquals("/packages/..%2Fsecret.tar.gz", observedRawPath.get());
    }

    /**
     * Same finding again, via the other wire-protocol route that shares the same {@code proxyGet}
     * helper: the package name in {@code GET /simple/{package}/} is just as caller-supplied as
     * the filename in {@code GET /packages/{filename}}.
     */
    @Test
    void packageIndexTreatsASlashInThePackageNameAsLiteralNotAStructuralSeparator() {
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 404, "text/plain", "");
        });

        Response response = controller.packageIndex(headers("Bearer good"), DOMAIN, REPOSITORY, "../other-pkg");

        assertEquals(404, response.getStatus());
        assertEquals("/simple/..%2Fother-pkg/", observedRawPath.get());
    }

    @Test
    void uploadRejectsABodyWithNoDeterminableBoundary() {
        Response response = controller.upload(headers("Bearer good", "application/octet-stream"), DOMAIN, REPOSITORY,
                "not multipart".getBytes(StandardCharsets.UTF_8));

        assertEquals(400, response.getStatus());
    }

    @Test
    void uploadRejectsAMultipartBodyMissingNameOrContent() {
        byte[] body = multipart("boundary123", "version", "1.0.0");

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(400, response.getStatus());
    }

    @Test
    void uploadRejectsAFileAlreadyListedInTheBackendsSimpleIndex() {
        backend.createContext("/simple/my-pkg/",
                exchange -> respond(exchange, 200, "text/html", "<a href=\"my_pkg-1.0.tar.gz\">my_pkg-1.0.tar.gz</a>"));
        AtomicReference<Boolean> uploadReceived = new AtomicReference<>(false);
        backend.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                uploadReceived.set(true);
            }
            respond(exchange, 200, "text/plain", "");
        });
        byte[] body = multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.tar.gz", "new content");

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(409, response.getStatus());
        assertEquals(false, uploadReceived.get());
    }

    /**
     * The fix for a real finding: a plain substring search on the whole index response body
     * would wrongly treat the uploaded filename as already published if it happened to be a
     * substring of a different, longer real entry, e.g. a detached {@code .asc} signature file
     * whose own name contains the base file's name as a prefix.
     */
    @Test
    void uploadDoesNotTreatAShorterFilenameAsAlreadyListedWhenItIsOnlyASubstringOfALongerOne() {
        backend.createContext("/simple/my-pkg/", exchange -> respond(exchange, 200, "text/html",
                "<a href=\"my_pkg-1.0.0.tar.gz.asc\">my_pkg-1.0.0.tar.gz.asc</a>"));
        AtomicReference<Boolean> uploadReceived = new AtomicReference<>(false);
        backend.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                uploadReceived.set(true);
            }
            respond(exchange, 200, "text/plain", "");
        });
        byte[] body = multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.0.tar.gz", "new content");

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(200, response.getStatus());
        assertEquals(true, uploadReceived.get());
    }

    /**
     * The fix for a real finding: the upload preflight's own index-check request (as opposed to
     * {@code packageIndex}'s, already covered above) built its path the same unsafe way, and
     * nothing exercised it with a reserved character.
     */
    @Test
    void uploadExistenceCheckTreatsAHashInThePackageNameAsLiteralNotAUriFragment() {
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                observedRawPath.set(exchange.getRequestURI().getRawPath());
                respond(exchange, 404, "text/plain", "");
            } else {
                respond(exchange, 200, "text/plain", "");
            }
        });
        byte[] body = multipartWithFile("boundary123", "my#pkg", "my_pkg-1.0.0.tar.gz", "content");

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(200, response.getStatus());
        assertEquals("/simple/my%23pkg/", observedRawPath.get());
    }

    /**
     * Same finding, the preflight's other request: once the simple index confirms a match, it
     * fetches the existing file's bytes for the content comparison, at a path built from the
     * same caller-supplied filename. Uses {@code ?}, not {@code #} or {@code /}: both of those
     * are already meaningful to the index's own href parsing (a root-relative directory prefix
     * and a hash-fragment suffix, both stripped the way a real pypiserver response uses them),
     * so a filename containing either could never round-trip through the exact-match check at
     * all, real bug or not; {@code ?} isn't special to that parsing, only to building the URI.
     */
    @Test
    void uploadExistenceCheckTreatsAQuestionMarkInTheFilenameAsLiteralNotAUriQueryStringWhenFetchingTheExistingFile() {
        String filename = "my_pkg-1.0.0?sig.tar.gz";
        backend.createContext("/simple/my-pkg/", exchange -> respond(exchange, 200, "text/html",
                "<a href=\"" + filename + "\">" + filename + "</a>"));
        AtomicReference<String> observedRawPath = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            observedRawPath.set(exchange.getRequestURI().getRawPath());
            respond(exchange, 200, "application/octet-stream", "different content");
        });
        byte[] body = multipartWithFile("boundary123", "my-pkg", filename, "new content");

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(409, response.getStatus());
        assertEquals("/packages/my_pkg-1.0.0%3Fsig.tar.gz", observedRawPath.get());
    }

    @Test
    void uploadOfByteIdenticalContentSucceedsIdempotentlyWithoutForwardingToTheBackend() {
        String sameContent = "same content";
        backend.createContext("/simple/my-pkg/",
                exchange -> respond(exchange, 200, "text/html", "<a href=\"my_pkg-1.0.tar.gz\">my_pkg-1.0.tar.gz</a>"));
        backend.createContext("/packages/my_pkg-1.0.tar.gz",
                exchange -> respond(exchange, 200, "application/octet-stream", sameContent));
        AtomicReference<Boolean> uploadReceived = new AtomicReference<>(false);
        backend.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                uploadReceived.set(true);
            }
            respond(exchange, 200, "text/plain", "");
        });
        byte[] body = multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.tar.gz", sameContent);

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(200, response.getStatus());
        assertEquals(false, uploadReceived.get());
    }

    @Test
    void uploadForwardsANewFileToTheBackendWhenNothingConflicts() {
        backend.createContext("/simple/my-pkg/", exchange -> respond(exchange, 404, "text/plain", ""));
        AtomicReference<String> receivedBody = new AtomicReference<>();
        backend.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            respond(exchange, 200, "text/plain", "");
        });
        byte[] body = multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.tar.gz", "new content");

        Response response = controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body);

        assertEquals(200, response.getStatus());
        assertTrue(receivedBody.get().contains("new content"));
    }

    @Test
    void uploadFailsClosedWhenTheExistenceCheckCannotReachTheBackend() {
        // Nothing listens on this port: the existence check's own connect attempt fails, which
        // must reject the upload rather than silently treating the failure as "not published yet."
        when(pypiserver.ensureReady(eq("pypi-repo-id"), any())).thenReturn("http://127.0.0.1:1");
        byte[] body = multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.tar.gz", "content");

        assertThrows(IllegalStateException.class, () -> controller.upload(
                headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY, body));
    }

    @Test
    void concurrentUploadsOfDifferentContentForTheSameNewFilenameAreSerialized() throws Exception {
        CountDownLatch holdFirstCheck = new CountDownLatch(1);
        CountDownLatch firstCheckEntered = new CountDownLatch(1);
        AtomicInteger indexHits = new AtomicInteger(0);
        AtomicBoolean published = new AtomicBoolean(false);

        backend.createContext("/simple/my-pkg/", exchange -> {
            if (indexHits.incrementAndGet() == 1) {
                firstCheckEntered.countDown();
                try {
                    assertTrue(holdFirstCheck.await(5, TimeUnit.SECONDS), "test never released the first check");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (published.get()) {
                respond(exchange, 200, "text/html", "<a href=\"/packages/my_pkg-1.0.tar.gz\">my_pkg-1.0.tar.gz</a>");
            } else {
                respond(exchange, 404, "text/plain", "");
            }
        });
        backend.createContext("/packages/my_pkg-1.0.tar.gz",
                exchange -> respond(exchange, 200, "application/octet-stream", "content-A"));
        backend.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                published.set(true);
            }
            respond(exchange, 200, "text/plain", "");
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Response> first = pool.submit(() -> controller.upload(
                    headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY,
                    multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.tar.gz", "content-A")));
            assertTrue(firstCheckEntered.await(5, TimeUnit.SECONDS), "first upload never started its check");

            Future<Response> second = pool.submit(() -> controller.upload(
                    headers("Bearer good", "multipart/form-data; boundary=boundary123"), DOMAIN, REPOSITORY,
                    multipartWithFile("boundary123", "my-pkg", "my_pkg-1.0.tar.gz", "content-B")));

            // The second upload must block on the same repository's lock, held by the first, and
            // so cannot even reach the backend yet: without the lock this would race ahead and
            // both uploads could observe "not published yet" before either one's POST lands.
            assertThrows(TimeoutException.class, () -> second.get(500, TimeUnit.MILLISECONDS));
            assertEquals(1, indexHits.get(), "second upload must not have reached the backend yet");

            holdFirstCheck.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(409, second.get(5, TimeUnit.SECONDS).getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    private static HttpHeaders headers(String authorization) {
        return headers(authorization, "text/plain");
    }

    private static HttpHeaders headers(String authorization, String contentType) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString("Authorization")).thenReturn(authorization);
        when(headers.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn(contentType);
        return headers;
    }

    private static byte[] multipart(String boundary, String fieldName, String value) {
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n"
                + value + "\r\n"
                + "--" + boundary + "--\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] multipartWithFile(String boundary, String packageName, String filename, String content) {
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + packageName + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + content + "\r\n"
                + "--" + boundary + "--\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType,
                                 String body) throws java.io.IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType,
                                 byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
