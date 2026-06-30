package io.github.hectorvent.floci.services.appsync.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.util.AppSyncUtil;

import java.util.*;

public class AppSyncVtlContext {

    private final Map<String, Object> contextMap;
    private final AppSyncUtil util;
    private final List<Map<String, Object>> appendedErrors;

    public AppSyncVtlContext(
            Map<String, Object> arguments,
            Map<String, Object> source,
            Map<String, Object> identity,
            Map<String, Object> request,
            Map<String, Object> info,
            Map<String, Object> stash,
            Map<String, Object> prev,
            Object result,
            String authType,
            ObjectMapper objectMapper
    ) {
        this.contextMap = buildContextMap(arguments, source, identity, request, info, stash, prev, result);
        this.appendedErrors = new ArrayList<>();
        this.util = new AppSyncUtil(objectMapper);
        this.util.setErrorList(this.appendedErrors);
        this.util.setAuthTypeValue(authType);
    }

    private AppSyncVtlContext(Builder builder) {
        this.contextMap = buildContextMap(
                builder.arguments, builder.source, builder.identity, builder.request,
                builder.info, builder.stash, builder.prev, builder.result);
        this.appendedErrors = new ArrayList<>();
        this.util = new AppSyncUtil(builder.objectMapper);
        this.util.setErrorList(this.appendedErrors);
        this.util.setAuthTypeValue(builder.authType);
    }

    private static Map<String, Object> buildContextMap(
            Map<String, Object> arguments,
            Map<String, Object> source,
            Map<String, Object> identity,
            Map<String, Object> request,
            Map<String, Object> info,
            Map<String, Object> stash,
            Map<String, Object> prev,
            Object result) {
        Map<String, Object> map = new HashMap<>();
        map.put("arguments", arguments != null ? arguments : Map.of());
        map.put("source", source != null ? source : Map.of());
        map.put("result", result);
        map.put("identity", identity != null ? identity : Map.of());
        map.put("request", request != null ? request : Map.of("headers", Map.of()));
        map.put("stash", stash != null ? stash : new HashMap<>());
        map.put("prev", prev);
        map.put("info", info != null ? info : Map.of());
        map.put("error", null);
        return map;
    }

    public static Builder builder(ObjectMapper objectMapper) {
        return new Builder(objectMapper);
    }

    public Map<String, Object> getContextMap() {
        return contextMap;
    }

    public AppSyncUtil getUtil() {
        return util;
    }

    public List<Map<String, Object>> getAppendedErrors() {
        return appendedErrors;
    }

    public static class Builder {
        private final ObjectMapper objectMapper;
        private Map<String, Object> arguments;
        private Map<String, Object> source;
        private Map<String, Object> identity;
        private Map<String, Object> request;
        private Map<String, Object> info;
        private Map<String, Object> stash;
        private Map<String, Object> prev;
        private Object result;
        private String authType;

        Builder(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public Builder arguments(Map<String, Object> arguments) {
            this.arguments = arguments;
            return this;
        }

        public Builder source(Map<String, Object> source) {
            this.source = source;
            return this;
        }

        public Builder identity(Map<String, Object> identity) {
            this.identity = identity;
            return this;
        }

        public Builder request(Map<String, Object> request) {
            this.request = request;
            return this;
        }

        public Builder info(Map<String, Object> info) {
            this.info = info;
            return this;
        }

        public Builder stash(Map<String, Object> stash) {
            this.stash = stash;
            return this;
        }

        public Builder prev(Map<String, Object> prev) {
            this.prev = prev;
            return this;
        }

        public Builder result(Object result) {
            this.result = result;
            return this;
        }

        public Builder authType(String authType) {
            this.authType = authType;
            return this;
        }

        public AppSyncVtlContext build() {
            return new AppSyncVtlContext(this);
        }
    }
}
