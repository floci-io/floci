package io.floci.aws.services.mwaa.proxy;

import io.floci.aws.core.common.docker.ContainerExec;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.KeyCertOptions;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP relay for a single MWAA environment's webserver traffic.
 *
 * <p>Every request is transparently forwarded to the backend Airflow container's internal
 * {@code host:8080} and the response streamed back verbatim, using the same Vert.x
 * {@code HttpClient}/{@code HttpServer} style as {@code ElbV2DataPlane} — except for
 * {@code POST /aws_mwaa/cli}, which this proxy intercepts itself: it validates the
 * {@code Authorization: Bearer <token>} header against the token(s) minted by
 * {@code CreateCliToken} for this environment, then runs the request body (the raw Airflow CLI
 * command, e.g. {@code "dags list"}) inside the Airflow container and returns the
 * AWS-documented {@code {"stdout": "<base64>", "stderr": "<base64>"}} shape.
 *
 * <p>{@code POST /aws_mwaa/login} is intercepted too. A valid {@code CreateWebLoginToken} token in
 * its {@code token} form field signs in to Airflow's own login form as the environment's admin, and
 * the response hands back Airflow's {@code session} cookie with a redirect to {@code /home}, the
 * cookie Amazon MWAA's REST API guide has clients send to {@code /api/v1}.
 */
public class MwaaWebProxy {

    private static final Logger LOG = Logger.getLogger(MwaaWebProxy.class);
    private static final String CLI_PATH = "/aws_mwaa/cli";
    private static final String LOGIN_PATH = "/aws_mwaa/login";
    private static final String AIRFLOW_LOGIN_PATH = "/login/";
    private static final Pattern CSRF_TOKEN_INPUT = Pattern.compile("<input[^>]*name=\"csrf_token\"[^>]*>");
    private static final Pattern VALUE_ATTRIBUTE = Pattern.compile("value=\"([^\"]*)\"");
    // CLI execs get their own small pool, shared by name across environments, so a burst of
    // slow Docker execs cannot occupy the Vert.x worker pool the rest of the emulator uses.
    private static final String CLI_WORKER_POOL_NAME = "mwaa-cli";
    private static final int CLI_WORKER_POOL_SIZE = 4;

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailers",
            "proxy-authorization", "proxy-authenticate");

    private final String environmentName;
    private final Vertx vertx;
    private final String backendHost;
    private final int backendPort;
    private final TokenValidator cliTokenValidator;
    private final CliExecutor cliExecutor;
    private final TokenValidator webTokenValidator;
    private final AirflowAccount airflowAccount;
    private final WorkerExecutor cliWorkers;

    private HttpServer server;
    private HttpClient client;

    public MwaaWebProxy(String environmentName, Vertx vertx, String backendHost, int backendPort,
                        TokenValidator cliTokenValidator, CliExecutor cliExecutor,
                        TokenValidator webTokenValidator, AirflowAccount airflowAccount) {
        this.environmentName = environmentName;
        this.vertx = vertx;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.cliTokenValidator = cliTokenValidator;
        this.cliExecutor = cliExecutor;
        this.webTokenValidator = webTokenValidator;
        this.airflowAccount = airflowAccount;
        this.cliWorkers = vertx.createSharedWorkerExecutor(CLI_WORKER_POOL_NAME, CLI_WORKER_POOL_SIZE);
    }

    /** Starts listening on {@code proxyPort} over plain HTTP, blocking until bound (or throwing on failure). */
    public void start(int proxyPort) throws Exception {
        start(proxyPort, null);
    }

    /**
     * Starts listening on {@code proxyPort}, blocking until bound (or throwing on failure). With
     * {@code serverKeyCertOptions} the proxy serves HTTPS with that key and certificate.
     */
    public void start(int proxyPort, KeyCertOptions serverKeyCertOptions) throws Exception {
        client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5000)
                .setKeepAlive(true));
        HttpServerOptions serverOptions = new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(proxyPort);
        if (serverKeyCertOptions != null) {
            serverOptions.setSsl(true).setKeyCertOptions(serverKeyCertOptions);
        }
        server = vertx.createHttpServer(serverOptions);
        server.requestHandler(this::handleRequest);
        try {
            server.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // The manager only tracks proxies that started, so release what this one holds here.
            stop();
            throw new IllegalStateException("Could not bind MWAA web proxy for environment "
                    + environmentName + " on port " + proxyPort, e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            stop();
            throw new IllegalStateException("Timed out binding MWAA web proxy for environment "
                    + environmentName + " on port " + proxyPort, e);
        }
        LOG.infov("MWAA web proxy started for environment {0} on port {1} -> {2}:{3}",
                environmentName, String.valueOf(proxyPort), backendHost, String.valueOf(backendPort));
    }

    public void stop() {
        if (server != null) {
            server.close();
        }
        if (client != null) {
            client.close();
        }
        cliWorkers.close();
    }

    private void handleRequest(HttpServerRequest req) {
        if (req.method() == HttpMethod.POST && CLI_PATH.equals(req.path())) {
            handleCli(req);
        } else if (req.method() == HttpMethod.POST && LOGIN_PATH.equals(req.path())) {
            handleLogin(req);
        } else {
            forward(req);
        }
    }

    private void handleCli(HttpServerRequest req) {
        String token = extractBearerToken(req.getHeader("Authorization"));
        if (token == null || !cliTokenValidator.isValid(environmentName, token)) {
            req.response().setStatusCode(401)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"message\":\"Missing or invalid CLI token\"}");
            return;
        }
        req.bodyHandler(body -> {
            String command = body.toString(StandardCharsets.UTF_8).trim();
            // The Docker exec can block for up to 30 seconds, so keep it off the event loop.
            // Unordered: independent CLI calls should not queue behind each other.
            cliWorkers.<ContainerExec.Result>executeBlocking(() -> cliExecutor.execute(command), false)
                    .onSuccess(result -> {
                        String stdoutB64 = Base64.getEncoder().encodeToString(result.stdout().getBytes(StandardCharsets.UTF_8));
                        String stderrB64 = Base64.getEncoder().encodeToString(result.stderr().getBytes(StandardCharsets.UTF_8));
                        req.response()
                                .putHeader("Content-Type", "application/json")
                                .end("{\"stdout\":\"" + stdoutB64 + "\",\"stderr\":\"" + stderrB64 + "\"}");
                    })
                    .onFailure(e -> {
                        LOG.warnv("MWAA CLI exec failed for environment {0}: {1}", environmentName, e.getMessage());
                        req.response().setStatusCode(500)
                                .putHeader("Content-Type", "application/json")
                                .end("{\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
                    });
        });
    }

    private void handleLogin(HttpServerRequest req) {
        req.setExpectMultipart(true);
        req.endHandler(ignored -> {
            String token = req.getFormAttribute("token");
            if (token == null || !webTokenValidator.isValid(environmentName, token)) {
                req.response().setStatusCode(401)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"message\":\"Missing, invalid or expired web login token\"}");
                return;
            }
            signInToAirflow()
                    .onSuccess(cookies -> {
                        HttpServerResponse response = req.response().setStatusCode(302).putHeader("Location", "/home");
                        cookies.forEach(cookie -> response.headers().add("Set-Cookie", cookie));
                        response.end();
                    })
                    .onFailure(e -> {
                        LOG.warnv("MWAA web login failed for environment {0}: {1}", environmentName, e.getMessage());
                        req.response().setStatusCode(502)
                                .putHeader("Content-Type", "application/json")
                                .end("{\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
                    });
        });
    }

    /**
     * Signs in through Airflow's login form the way a browser does: GET it for the CSRF token and
     * the anonymous session cookie, POST the admin credentials back with both, and return the
     * {@code Set-Cookie} headers of the redirect Airflow answers a successful sign-in with.
     */
    private Future<List<String>> signInToAirflow() {
        return client.request(backendRequest(HttpMethod.GET, AIRFLOW_LOGIN_PATH))
                .compose(HttpClientRequest::send)
                .compose(loginPage -> loginPage.body().compose(body -> client.request(backendRequest(HttpMethod.POST,
                                AIRFLOW_LOGIN_PATH))
                        .compose(post -> {
                            String cookie = sessionCookie(loginPage.cookies());
                            if (cookie != null) {
                                post.putHeader("Cookie", cookie);
                            }
                            post.putHeader("Content-Type", "application/x-www-form-urlencoded");
                            return post.send(loginForm(csrfToken(body.toString(StandardCharsets.UTF_8)), airflowAccount));
                        })))
                .compose(response -> response.body().compose(ignored -> signedInCookies(response)));
    }

    private RequestOptions backendRequest(HttpMethod method, String uri) {
        return new RequestOptions()
                .setHost(backendHost)
                .setPort(backendPort)
                .setURI(uri)
                .setMethod(method);
    }

    private static Future<List<String>> signedInCookies(HttpClientResponse response) {
        String location = response.getHeader("Location");
        if (response.statusCode() != 302 || sessionCookie(response.cookies()) == null
                || !leavesTheLoginForm(location)) {
            return Future.failedFuture("Airflow did not accept the environment's admin sign-in (HTTP "
                    + response.statusCode() + (location != null ? ", redirected to " + location : "") + ")");
        }
        return Future.succeededFuture(response.cookies());
    }

    /**
     * Whether a sign-in's redirect leads away from the login form. Flask-AppBuilder answers refused
     * credentials with a redirect back to it, carrying a session cookie that only holds the flashed
     * message.
     */
    static boolean leavesTheLoginForm(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        try {
            String path = URI.create(location).getPath();
            return path != null && !path.startsWith("/login");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String csrfToken(String loginPage) {
        Matcher input = CSRF_TOKEN_INPUT.matcher(loginPage);
        if (!input.find()) {
            return null;
        }
        Matcher value = VALUE_ATTRIBUTE.matcher(input.group());
        return value.find() ? value.group(1) : null;
    }

    static String sessionCookie(List<String> setCookieHeaders) {
        for (String header : setCookieHeaders) {
            if (header.startsWith("session=")) {
                int end = header.indexOf(';');
                return end >= 0 ? header.substring(0, end) : header;
            }
        }
        return null;
    }

    static Buffer loginForm(String csrfToken, AirflowAccount account) {
        String form = "username=" + URLEncoder.encode(account.username(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(account.password(), StandardCharsets.UTF_8);
        if (csrfToken != null) {
            form += "&csrf_token=" + URLEncoder.encode(csrfToken, StandardCharsets.UTF_8);
        }
        return Buffer.buffer(form);
    }

    private void forward(HttpServerRequest req) {
        req.bodyHandler(body -> {
            RequestOptions opts = new RequestOptions()
                    .setHost(backendHost)
                    .setPort(backendPort)
                    .setURI(req.uri())
                    .setMethod(req.method());
            client.request(opts)
                    .onSuccess(clientReq -> {
                        req.headers().forEach(h -> {
                            if (!HOP_BY_HOP_HEADERS.contains(h.getKey().toLowerCase())) {
                                clientReq.putHeader(h.getKey(), h.getValue());
                            }
                        });
                        clientReq.putHeader("Host", backendHost + ":" + backendPort);
                        clientReq.send(body)
                                .onSuccess(resp -> {
                                    req.response().setStatusCode(resp.statusCode());
                                    resp.headers().forEach(h -> {
                                        if (!HOP_BY_HOP_HEADERS.contains(h.getKey().toLowerCase())) {
                                            req.response().putHeader(h.getKey(), h.getValue());
                                        }
                                    });
                                    resp.body()
                                            .onSuccess(req.response()::end)
                                            .onFailure(err -> req.response().setStatusCode(502).end("Body error"));
                                })
                                .onFailure(err -> req.response().setStatusCode(502).end("Bad gateway"));
                    })
                    .onFailure(err -> req.response().setStatusCode(503).end("Service unavailable"));
        });
    }

    static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static String escapeJson(String message) {
        return message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Determines which path/method combination is routed to the CLI handler. Used by tests. */
    public static boolean isCliRequest(String method, String path) {
        return "POST".equalsIgnoreCase(method) && CLI_PATH.equals(path);
    }

    @FunctionalInterface
    public interface TokenValidator {
        boolean isValid(String environmentName, String token);
    }

    /** The Airflow web UI account a valid web login token signs in as. */
    public record AirflowAccount(String username, String password) {
    }

    @FunctionalInterface
    public interface CliExecutor {
        ContainerExec.Result execute(String cliCommand) throws Exception;
    }
}
