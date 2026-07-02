package io.github.hectorvent.floci.services.appsync.graphql.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class AppSyncUtil {

    private final ObjectMapper objectMapper;
    private final StrUtil strUtil;
    private final TimeUtil timeUtil;
    private final MathUtil mathUtil;
    private final DynamoDbUtil dynamoDbUtil;
    private final TransformUtil transformUtil;
    private final ListUtil listUtil;
    private final MapUtil mapUtil;
    private List<Map<String, Object>> errorList;
    private String authTypeValue;

    public AppSyncUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.strUtil = new StrUtil();
        this.timeUtil = new TimeUtil();
        this.mathUtil = new MathUtil();
        this.dynamoDbUtil = new DynamoDbUtil(objectMapper);
        this.transformUtil = new TransformUtil(objectMapper);
        this.listUtil = new ListUtil();
        this.mapUtil = new MapUtil();
    }

    public void setErrorList(List<Map<String, Object>> errorList) {
        this.errorList = errorList;
    }

    public void setAuthTypeValue(String authTypeValue) {
        this.authTypeValue = authTypeValue;
    }

    public StrUtil getStr() { return strUtil; }
    public TimeUtil getTime() { return timeUtil; }
    public MathUtil getMath() { return mathUtil; }
    public DynamoDbUtil getDynamodb() { return dynamoDbUtil; }
    public TransformUtil getTransform() { return transformUtil; }
    public ListUtil getList() { return listUtil; }
    public MapUtil getMap() { return mapUtil; }

    public String escapeJavaScript(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\'' -> sb.append("\\'");
                case '/' -> sb.append("\\/");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public String urlEncode(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public String urlDecode(String s) {
        if (s == null) return "";
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public String base64Encode(byte[] data) {
        if (data == null) return "";
        return Base64.getEncoder().encodeToString(data);
    }

    public String base64Decode(String s) {
        if (s == null) return "";
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    public Object parseJson(String s) {
        if (s == null || s.isEmpty()) return Map.of();
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public String toJson(Object o) {
        try {
            if (o instanceof Map<?, ?> map && map.containsKey("fieldName")
                    && map.containsKey("parentTypeName")) {
                Map<String, Object> filtered = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = entry.getKey().toString();
                    if (!key.equals("selectionSetList") && !key.equals("selectionSetGraphQL")) {
                        filtered.put(key, entry.getValue());
                    }
                }
                return objectMapper.writeValueAsString(filtered);
            }
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    public String autoId() {
        return UUID.randomUUID().toString();
    }

    public String quiet(Object o) {
        return "";
    }

    public String qr(Object o) {
        return quiet(o);
    }

    public boolean matches(String pattern, String value) {
        if (pattern == null || value == null) return false;
        return Pattern.matches(pattern, value);
    }

    public Object defaultIfNull(Object value, Object defaultValue) {
        return value != null ? value : defaultValue;
    }

    public String defaultIfNullOrEmpty(String value, String defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        return value;
    }

    public String defaultIfNullOrBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        return value;
    }

    public boolean isNull(Object value) {
        return value == null;
    }

    public boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isString(Object value) {
        return value instanceof String;
    }

    public boolean isNumber(Object value) {
        return value instanceof Number;
    }

    public boolean isBoolean(Object value) {
        return value instanceof Boolean;
    }

    public boolean isList(Object value) {
        return value instanceof List<?>;
    }

    public boolean isMap(Object value) {
        return value instanceof Map<?, ?>;
    }

    public String typeOf(Object value) {
        if (value == null) return "Null";
        if (value instanceof String) return "String";
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof Number) return "Number";
        if (value instanceof List<?>) return "List";
        if (value instanceof Map<?, ?>) return "Map";
        return "Object";
    }

    public String authType() {
        return authTypeValue;
    }

    public void error(String message) {
        throw new VtlErrorSignal(message, "Unknown", null, null);
    }

    public void error(String message, String errorType) {
        throw new VtlErrorSignal(message, errorType, null, null);
    }

    public void error(String message, String errorType, Object data) {
        throw new VtlErrorSignal(message, errorType, data, null);
    }

    public void error(String message, String errorType, Object data, Object errorInfo) {
        throw new VtlErrorSignal(message, errorType, data, errorInfo);
    }

    public void appendError(String message) {
        appendErrorInternal(message, "Unknown", null, null);
    }

    public void appendError(String message, String errorType) {
        appendErrorInternal(message, errorType, null, null);
    }

    public void appendError(String message, String errorType, Object data) {
        appendErrorInternal(message, errorType, data, null);
    }

    public void appendError(String message, String errorType, Object data, Object errorInfo) {
        appendErrorInternal(message, errorType, data, errorInfo);
    }

    private void appendErrorInternal(String message, String errorType, Object data, Object errorInfo) {
        if (errorList != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", message);
            error.put("type", errorType);
            if (data != null) error.put("data", data);
            if (errorInfo != null) error.put("errorInfo", errorInfo);
            errorList.add(error);
        }
    }

    public void unauthorized() {
        throw new VtlErrorSignal("Not Authorized", "Unauthorized", null, null);
    }

    public void validate(boolean condition, String message) {
        validate(condition, message, "CustomTemplateException");
    }

    public void validate(boolean condition, String message, String errorType) {
        validate(condition, message, errorType, null);
    }

    public void validate(boolean condition, String message, String errorType, Object data) {
        if (!condition) {
            throw new VtlErrorSignal(message, errorType, data, null);
        }
    }
}
