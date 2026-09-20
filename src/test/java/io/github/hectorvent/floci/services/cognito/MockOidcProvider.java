package io.github.hectorvent.floci.services.cognito;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MockOidcProvider implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, String> authorizationParameters = new ConcurrentHashMap<>();
    private volatile String tokenRequestBody;
    private volatile String userInfoAuthorization;

    MockOidcProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/authorize", exchange -> {
            authorizationParameters.putAll(queryParameters(exchange.getRequestURI().getRawQuery()));
            respond(exchange, 302, "");
        });
        server.createContext("/token", exchange -> {
            tokenRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "{\"access_token\":\"provider-access-token\",\"token_type\":\"Bearer\"}");
        });
        server.createContext("/userinfo", exchange -> {
            userInfoAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 200, "{\"sub\":\"provider-subject\",\"email\":\"federated@example.com\"}");
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Map<String, String> authorizationParameters() {
        return new LinkedHashMap<>(authorizationParameters);
    }

    String tokenRequestBody() {
        return tokenRequestBody;
    }

    String userInfoAuthorization() {
        return userInfoAuthorization;
    }

    private Map<String, String> queryParameters(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2) {
                parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
