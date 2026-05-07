package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ElbV2DataPlane {

    private static final Logger LOG = Logger.getLogger(ElbV2DataPlane.class);

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailers", "proxy-authorization", "proxy-authenticate"
    );

    @Inject
    Vertx vertx;

    @Inject
    ElbV2Service elbV2Service;

    @Inject
    ElbV2HealthChecker healthChecker;

    @Inject
    EmulatorConfig config;

    @Inject
    LambdaService lambdaService;

    @Inject
    ObjectMapper objectMapper;

    private final Map<String, HttpServer> servers = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<List<CompiledRule>>> ruleChains = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rrCounters = new ConcurrentHashMap<>();
    private final Map<String, String> listenerRegions = new ConcurrentHashMap<>();

    private HttpClient proxyClient;

    @PostConstruct
    void init() {
        proxyClient = vertx.createHttpClient(new HttpClientOptions()
                .setMaxPoolSize(100)
                .setConnectTimeout(5000)
                .setKeepAlive(true));
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, HttpServer> e : servers.entrySet()) {
            e.getValue().close();
        }
        servers.clear();
        ruleChains.clear();
        rrCounters.clear();
        listenerRegions.clear();
    }

    public void startListener(Listener listener, String region, List<Rule> rules) {
        if (config.services().elbv2().mock()) {
            return;
        }
        String listenerArn = listener.getListenerArn();
        List<CompiledRule> compiled = compileRules(rules);
        ruleChains.put(listenerArn, new AtomicReference<>(compiled));
        listenerRegions.put(listenerArn, region);

        HttpServer server = vertx.createHttpServer(new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(listener.getPort()));

        server.requestHandler(req -> handleRequest(req, listenerArn, region));
        server.listen()
                .onSuccess(s -> LOG.infov("ELBv2 listener started on port {0} for {1}", listener.getPort(), listenerArn))
                .onFailure(err -> LOG.warnv("ELBv2 listener failed to start on port {0}: {1}", listener.getPort(), err.getMessage()));

        servers.put(listenerArn, server);
    }

    public void stopListener(String listenerArn) {
        HttpServer server = servers.remove(listenerArn);
        if (server != null) {
            server.close();
        }
        ruleChains.remove(listenerArn);
        listenerRegions.remove(listenerArn);
    }

    public void recompileRules(String listenerArn, List<Rule> rules) {
        AtomicReference<List<CompiledRule>> ref = ruleChains.get(listenerArn);
        if (ref != null) {
            ref.set(compileRules(rules));
        }
    }

    private void handleRequest(io.vertx.core.http.HttpServerRequest req, String listenerArn, String region) {
        AtomicReference<List<CompiledRule>> ref = ruleChains.get(listenerArn);
        if (ref == null) {
            req.response().setStatusCode(502).end("No rule chain");
            return;
        }
        List<CompiledRule> chain = ref.get();
        for (CompiledRule compiled : chain) {
            if (compiled.matches(req)) {
                executeAction(req, compiled.action, region);
                return;
            }
        }
        req.response().setStatusCode(502).end("No matching rule");
    }

    private void executeAction(io.vertx.core.http.HttpServerRequest req, Action action, String region) {
        if (action == null) {
            req.response().setStatusCode(502).end("No action");
            return;
        }
        switch (action.getType() != null ? action.getType() : "") {
            case "forward" -> executeForward(req, action, region);
            case "redirect" -> executeRedirect(req, action);
            case "fixed-response" -> executeFixedResponse(req, action);
            default -> req.response().setStatusCode(502).end("Unsupported action type");
        }
    }

    private void executeForward(io.vertx.core.http.HttpServerRequest req, Action action, String region) {
        String tgArn = resolveTgArn(action);
        if (tgArn == null) {
            req.response().setStatusCode(502).end("No target group");
            return;
        }
        TargetGroup tg = elbV2Service.getTargetGroup(region, tgArn);
        if (tg == null) {
            req.response().setStatusCode(502).end("Target group not found");
            return;
        }

        if ("lambda".equals(tg.getTargetType())) {
            List<TargetDescription> targets = tg.getTargets();
            if (targets.isEmpty()) {
                req.response().setStatusCode(503).end("No Lambda targets registered");
                return;
            }
            String functionArn = targets.get(0).getId();
            invokeLambdaTarget(req, functionArn, region);
            return;
        }

        List<TargetDescription> allTargets = tg.getTargets();
        List<TargetDescription> healthy = allTargets.stream()
                .filter(t -> healthChecker.isHealthy(tgArn, t, ElbV2HealthChecker.effectivePort(t, tg)))
                .collect(Collectors.toList());
        List<TargetDescription> candidates = healthy.isEmpty() ? allTargets : healthy;
        if (candidates.isEmpty()) {
            req.response().setStatusCode(503).end("No targets available");
            return;
        }
        AtomicInteger counter = rrCounters.computeIfAbsent(tgArn, k -> new AtomicInteger(0));
        int idx = Math.abs(counter.getAndIncrement() % candidates.size());
        TargetDescription target = candidates.get(idx);
        int targetPort = ElbV2HealthChecker.effectivePort(target, tg);
        proxyRequest(req, target.getId(), targetPort);
    }

    private void invokeLambdaTarget(io.vertx.core.http.HttpServerRequest req, String functionArn, String region) {
        req.bodyHandler(body -> {
            try {
                Map<String, Object> event = buildAlbEvent(req, body);
                byte[] payload = objectMapper.writeValueAsBytes(event);
                InvokeResult result = lambdaService.invoke(region, functionArn, payload, InvocationType.RequestResponse);

                if (result.getFunctionError() != null) {
                    req.response().setStatusCode(502).end("Lambda function error: " + result.getFunctionError());
                    return;
                }

                if (result.getPayload() == null || result.getPayload().length == 0) {
                    req.response().setStatusCode(200).end();
                    return;
                }

                Map<String, Object> lambdaResp = objectMapper.readValue(result.getPayload(),
                        new TypeReference<Map<String, Object>>() {});

                int statusCode = 200;
                Object sc = lambdaResp.get("statusCode");
                if (sc != null) {
                    statusCode = ((Number) sc).intValue();
                }

                req.response().setStatusCode(statusCode);

                Object headers = lambdaResp.get("headers");
                if (headers instanceof Map<?, ?> headerMap) {
                    for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
                        req.response().putHeader(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }

                Object multiValueHeaders = lambdaResp.get("multiValueHeaders");
                if (multiValueHeaders instanceof Map<?, ?> mvh) {
                    for (Map.Entry<?, ?> entry : mvh.entrySet()) {
                        if (entry.getValue() instanceof List<?> values) {
                            for (Object v : values) {
                                req.response().putHeader(String.valueOf(entry.getKey()), String.valueOf(v));
                            }
                        }
                    }
                }

                Object responseBody = lambdaResp.get("body");
                Boolean isBase64 = (Boolean) lambdaResp.get("isBase64Encoded");
                if (responseBody == null) {
                    req.response().end();
                } else if (Boolean.TRUE.equals(isBase64)) {
                    byte[] decoded = Base64.getDecoder().decode(String.valueOf(responseBody));
                    req.response().end(Buffer.buffer(decoded));
                } else {
                    req.response().end(String.valueOf(responseBody));
                }
            } catch (Exception e) {
                LOG.errorf(e, "Error invoking Lambda target %s", functionArn);
                req.response().setStatusCode(502).end("Lambda invocation error");
            }
        });
    }

    private Map<String, Object> buildAlbEvent(io.vertx.core.http.HttpServerRequest req, Buffer body) {
        Map<String, Object> event = new HashMap<>();
        event.put("requestContext", Map.of("elb", Map.of("targetGroupArn", "")));
        event.put("httpMethod", req.method().name());
        event.put("path", req.path() != null ? req.path() : "/");

        Map<String, String> queryParams = new HashMap<>();
        Map<String, List<String>> multiValueQueryParams = new HashMap<>();
        String query = req.query();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String val = eq >= 0 ? pair.substring(eq + 1) : "";
                queryParams.putIfAbsent(key, val);
                multiValueQueryParams.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
            }
        }
        event.put("queryStringParameters", queryParams.isEmpty() ? null : queryParams);
        event.put("multiValueQueryStringParameters", multiValueQueryParams.isEmpty() ? null : multiValueQueryParams);

        Map<String, String> headers = new HashMap<>();
        Map<String, List<String>> multiValueHeaders = new HashMap<>();
        req.headers().forEach(entry -> {
            String key = entry.getKey().toLowerCase();
            headers.putIfAbsent(key, entry.getValue());
            multiValueHeaders.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getValue());
        });
        event.put("headers", headers);
        event.put("multiValueHeaders", multiValueHeaders);

        boolean isBase64 = false;
        String bodyStr = null;
        if (body != null && body.length() > 0) {
            String contentType = req.getHeader("Content-Type");
            if (contentType != null && !contentType.startsWith("text/") && !contentType.contains("json")
                    && !contentType.contains("xml") && !contentType.contains("form")) {
                bodyStr = Base64.getEncoder().encodeToString(body.getBytes());
                isBase64 = true;
            } else {
                bodyStr = body.toString(StandardCharsets.UTF_8);
            }
        }
        event.put("body", bodyStr);
        event.put("isBase64Encoded", isBase64);

        return event;
    }

    private String resolveTgArn(Action action) {
        if (action.getTargetGroupArn() != null) {
            return action.getTargetGroupArn();
        }
        List<Action.TargetGroupTuple> tuples = action.getTargetGroups();
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        double total = tuples.stream().mapToDouble(t -> t.getWeight() != null ? t.getWeight() : 1).sum();
        double roll = Math.random() * total;
        double cumulative = 0;
        for (Action.TargetGroupTuple tuple : tuples) {
            cumulative += (tuple.getWeight() != null ? tuple.getWeight() : 1);
            if (roll < cumulative) {
                return tuple.getTargetGroupArn();
            }
        }
        return tuples.get(tuples.size() - 1).getTargetGroupArn();
    }

    private void proxyRequest(io.vertx.core.http.HttpServerRequest req, String host, int port) {
        req.bodyHandler(body -> {
            RequestOptions opts = new RequestOptions()
                    .setHost(host)
                    .setPort(port)
                    .setURI(req.uri())
                    .setMethod(req.method());
            proxyClient.request(opts)
                    .onSuccess(clientReq -> {
                        req.headers().forEach(entry -> {
                            if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                                clientReq.putHeader(entry.getKey(), entry.getValue());
                            }
                        });
                        clientReq.putHeader("Host", host + ":" + port);
                        clientReq.send(body)
                                .onSuccess(resp -> {
                                    req.response().setStatusCode(resp.statusCode());
                                    resp.headers().forEach(entry -> {
                                        if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                                            req.response().putHeader(entry.getKey(), entry.getValue());
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

    private void executeRedirect(io.vertx.core.http.HttpServerRequest req, Action action) {
        String reqHost = req.host();
        String reqPort = "";
        if (reqHost != null && reqHost.contains(":")) {
            String[] parts = reqHost.split(":", 2);
            reqHost = parts[0];
            reqPort = parts[1];
        }
        String reqPath = req.path() != null ? req.path() : "/";
        String reqQuery = req.query();
        String reqProtocol = "HTTP";

        String protocol = action.getRedirectProtocol() != null ? action.getRedirectProtocol() : reqProtocol;
        String host = action.getRedirectHost() != null ? action.getRedirectHost() : reqHost;
        String portStr = action.getRedirectPort() != null ? action.getRedirectPort() : reqPort;
        String path = action.getRedirectPath() != null ? action.getRedirectPath() : reqPath;
        String query = action.getRedirectQuery() != null ? action.getRedirectQuery() : (reqQuery != null ? reqQuery : "");

        final String finalReqHost = reqHost;
        final String finalReqPort = reqPort;

        protocol = substitute(protocol, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        host = substitute(host, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        portStr = substitute(portStr, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        path = substitute(path, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);
        query = substitute(query, finalReqHost, finalReqPort, reqPath, reqProtocol, reqQuery);

        StringBuilder location = new StringBuilder(protocol.toLowerCase()).append("://").append(host);
        if (!portStr.isEmpty()) {
            location.append(":").append(portStr);
        }
        location.append(path);
        if (!query.isEmpty()) {
            location.append("?").append(query);
        }

        int statusCode = "HTTP_301".equals(action.getRedirectStatusCode()) ? 301 : 302;
        req.response()
                .setStatusCode(statusCode)
                .putHeader("Location", location.toString())
                .end();
    }

    private String substitute(String template, String host, String port, String path, String protocol, String query) {
        if (template == null) {
            return "";
        }
        String result = template
                .replace("#{host}", host != null ? host : "")
                .replace("#{port}", port != null ? port : "")
                .replace("#{path}", path != null ? path : "/")
                .replace("#{protocol}", protocol != null ? protocol : "HTTP");
        if (query != null && !query.isEmpty()) {
            result = result.replace("#{query}", query);
        } else {
            result = result.replace("#{query}", "");
        }
        return result;
    }

    private void executeFixedResponse(io.vertx.core.http.HttpServerRequest req, Action action) {
        int statusCode = 200;
        try {
            if (action.getFixedResponseStatusCode() != null) {
                statusCode = Integer.parseInt(action.getFixedResponseStatusCode());
            }
        } catch (NumberFormatException ignored) {
        }
        req.response().setStatusCode(statusCode);
        if (action.getFixedResponseContentType() != null) {
            req.response().putHeader("Content-Type", action.getFixedResponseContentType());
        }
        String body = action.getFixedResponseMessageBody() != null ? action.getFixedResponseMessageBody() : "";
        req.response().end(body);
    }

    private List<CompiledRule> compileRules(List<Rule> rules) {
        return rules.stream()
                .map(CompiledRule::new)
                .collect(Collectors.toList());
    }

    private Action getRoutingAction(Rule rule) {
        List<Action> actions = rule.getActions();
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        Action last = null;
        for (Action a : actions) {
            String type = a.getType();
            if ("forward".equals(type) || "redirect".equals(type) || "fixed-response".equals(type)) {
                last = a;
            }
        }
        return last;
    }

    private static boolean globMatches(String pattern, String text) {
        if (text == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder("(?i)");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.matches(regex.toString(), text);
    }

    private class CompiledRule {
        final Rule rule;
        final Action action;

        CompiledRule(Rule rule) {
            this.rule = rule;
            this.action = getRoutingAction(rule);
        }

        boolean matches(io.vertx.core.http.HttpServerRequest req) {
            if (rule.isDefault()) {
                return true;
            }
            for (RuleCondition condition : rule.getConditions()) {
                if (!matchesCondition(condition, req)) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesCondition(RuleCondition condition, io.vertx.core.http.HttpServerRequest req) {
            String field = condition.getField();
            if (field == null) {
                return true;
            }
            return switch (field) {
                case "host-header" -> {
                    List<String> patterns = condition.getHostHeaderValues().isEmpty()
                            ? condition.getValues()
                            : condition.getHostHeaderValues();
                    String host = req.host();
                    if (host != null && host.contains(":")) {
                        host = host.substring(0, host.indexOf(':'));
                    }
                    String effectiveHost = host;
                    yield patterns.stream().anyMatch(p -> globMatches(p, effectiveHost));
                }
                case "path-pattern" -> {
                    List<String> patterns = condition.getPathPatternValues().isEmpty()
                            ? condition.getValues()
                            : condition.getPathPatternValues();
                    String path = req.path();
                    yield patterns.stream().anyMatch(p -> globMatches(p, path));
                }
                case "http-header" -> {
                    String headerName = condition.getHttpHeaderName();
                    if (headerName == null) {
                        yield true;
                    }
                    String headerValue = req.getHeader(headerName);
                    yield condition.getHttpHeaderValues().stream().anyMatch(p -> globMatches(p, headerValue));
                }
                case "http-request-method" -> {
                    String method = req.method().name();
                    yield condition.getHttpMethodValues().stream()
                            .anyMatch(m -> m.equalsIgnoreCase(method));
                }
                case "query-string" -> {
                    String queryString = req.query();
                    Map<String, String> queryParams = parseQueryString(queryString);
                    yield condition.getQueryStringValues().stream().allMatch(pair -> {
                        String key = pair.getKey();
                        String valuePattern = pair.getValue();
                        if (key == null) {
                            return queryParams.values().stream().anyMatch(v -> globMatches(valuePattern, v));
                        }
                        String paramValue = queryParams.get(key);
                        return paramValue != null && globMatches(valuePattern, paramValue);
                    });
                }
                case "source-ip" -> true;
                default -> true;
            };
        }

        private Map<String, String> parseQueryString(String query) {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            if (query == null || query.isEmpty()) {
                return params;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq >= 0) {
                    params.put(pair.substring(0, eq), pair.substring(eq + 1));
                } else {
                    params.put(pair, "");
                }
            }
            return params;
        }
    }
}