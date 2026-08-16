package io.github.hectorvent.floci.services.mwaa.proxy;

import io.github.hectorvent.floci.services.mwaa.MwaaEnvironmentManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 */
public class MwaaWebProxy {

    private static final Logger LOG = Logger.getLogger(MwaaWebProxy.class);
    private static final String CLI_PATH = "/aws_mwaa/cli";

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailers",
            "proxy-authorization", "proxy-authenticate");

    private final String environmentName;
    private final Vertx vertx;
    private final String backendHost;
    private final int backendPort;
    private final CliTokenValidator tokenValidator;
    private final CliExecutor cliExecutor;

    private HttpServer server;
    private HttpClient client;

    public MwaaWebProxy(String environmentName, Vertx vertx, String backendHost, int backendPort,
                        CliTokenValidator tokenValidator, CliExecutor cliExecutor) {
        this.environmentName = environmentName;
        this.vertx = vertx;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.tokenValidator = tokenValidator;
        this.cliExecutor = cliExecutor;
    }

    /** Starts listening on {@code proxyPort}, blocking until bound (or throwing on failure). */
    public void start(int proxyPort) throws Exception {
        client = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5000)
                .setKeepAlive(true));
        server = vertx.createHttpServer(new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(proxyPort));
        server.requestHandler(this::handleRequest);
        try {
            server.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not bind MWAA web proxy for environment "
                    + environmentName + " on port " + proxyPort, e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
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
    }

    private void handleRequest(HttpServerRequest req) {
        if (req.method() == HttpMethod.POST && CLI_PATH.equals(req.path())) {
            handleCli(req);
        } else {
            forward(req);
        }
    }

    private void handleCli(HttpServerRequest req) {
        String token = extractBearerToken(req.getHeader("Authorization"));
        if (token == null || !tokenValidator.isValid(environmentName, token)) {
            req.response().setStatusCode(401)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"message\":\"Missing or invalid CLI token\"}");
            return;
        }
        req.bodyHandler(body -> {
            String command = body.toString(StandardCharsets.UTF_8).trim();
            try {
                MwaaEnvironmentManager.ExecResult result = cliExecutor.execute(command);
                String stdoutB64 = Base64.getEncoder().encodeToString(result.stdout().getBytes(StandardCharsets.UTF_8));
                String stderrB64 = Base64.getEncoder().encodeToString(result.stderr().getBytes(StandardCharsets.UTF_8));
                req.response()
                        .putHeader("Content-Type", "application/json")
                        .end("{\"stdout\":\"" + stdoutB64 + "\",\"stderr\":\"" + stderrB64 + "\"}");
            } catch (Exception e) {
                LOG.warnv("MWAA CLI exec failed for environment {0}: {1}", environmentName, e.getMessage());
                req.response().setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
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
    public interface CliTokenValidator {
        boolean isValid(String environmentName, String token);
    }

    @FunctionalInterface
    public interface CliExecutor {
        MwaaEnvironmentManager.ExecResult execute(String cliCommand) throws Exception;
    }
}
