package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable builder for an outgoing proxied HTTP request. Single source of
 * truth between the initial integration template + inbound request data and
 * the final {@link java.net.http.HttpRequest} sent to the backend.
 *
 * <p>Headers and query params are stored as multimaps to support
 * {@code append:header.X} and {@code append:querystring.X} actions.
 */
public class ProxyRequestBuilder {
    private String url;
    private String method;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
    private byte[] body;

    public ProxyRequestBuilder(String url, String method) {
        this.url = url;
        this.method = method;
    }

    public void setUrl(String url) { this.url = url; }
    public void setMethod(String method) { this.method = method; }
    public void setBody(byte[] body) { this.body = body; }

    public void overwriteHeader(String name, String value) {
        if (value == null) { headers.remove(name); return; }
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
    }
    public void appendHeader(String name, String value) {
        if (value == null) return;
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
    public void removeHeader(String name) { headers.remove(name); }

    public void overwriteQuery(String name, String value) {
        if (value == null) { queryParams.remove(name); return; }
        List<String> list = new ArrayList<>();
        list.add(value);
        queryParams.put(name, list);
    }
    public void appendQuery(String name, String value) {
        if (value == null) return;
        queryParams.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
    public void removeQuery(String name) { queryParams.remove(name); }

    public String url() { return url; }
    public String method() { return method; }
    public Map<String, List<String>> headers() { return headers; }
    public Map<String, List<String>> queryParams() { return queryParams; }
    public byte[] body() { return body; }
}
