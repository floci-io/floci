package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure request-routing logic for CloudFront distribution serving, implemented to the AWS data-plane
 * spec (verified against the CloudFront Developer Guide):
 *
 * <ul>
 *   <li>The requested path is normalized per RFC 3986 (collapse {@code //}, resolve {@code .}/{@code ..})
 *       before behavior matching; the raw path is what gets forwarded to the origin.</li>
 *   <li>Cache behaviors are evaluated in the order listed; the <em>default</em> behavior is always
 *       processed <em>last</em>. The first matching path pattern wins.</li>
 *   <li>Path patterns use {@code *} (0+ chars) and {@code ?} (exactly 1 char), are case-sensitive, and a
 *       leading {@code /} is optional (ignored).</li>
 *   <li>The default root object is returned <em>only</em> for the distribution root ({@code /}); it is
 *       never appended for subdirectory requests (this differs from S3 website index documents).</li>
 *   <li>The origin path is prepended to the request URI when forwarding to the origin.</li>
 * </ul>
 *
 * This class holds no state and does no I/O so the routing rules can be unit-tested in isolation.
 */
public final class CloudFrontRequestRouter {

    private CloudFrontRequestRouter() {
    }

    /**
     * Normalizes a viewer request path per RFC 3986 the way CloudFront does before behavior matching:
     * resolves {@code .} / {@code ..} segments and collapses repeated slashes. Always returns a path
     * beginning with {@code /}.
     */
    public static String normalizePath(String rawPath) {
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        boolean trailingSlash = path.length() > 1 && path.endsWith("/");
        Deque<String> out = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;   // collapses "//" and drops "."
            }
            if (seg.equals("..")) {
                if (!out.isEmpty()) {
                    out.removeLast();
                }
                continue;
            }
            out.addLast(seg);
        }
        if (out.isEmpty()) {
            return "/";
        }
        String joined = "/" + String.join("/", out);
        return trailingSlash ? joined + "/" : joined;
    }

    /** True when {@code path} is the distribution root. */
    public static boolean isRoot(String normalizedPath) {
        return normalizedPath == null || normalizedPath.isEmpty() || normalizedPath.equals("/");
    }

    /**
     * Returns the {@code targetOriginId} for a normalized path by evaluating the ordered cache
     * behaviors first (first match wins) and falling back to the default behavior last.
     */
    public static String matchTargetOriginId(DistributionConfig config, String normalizedPath) {
        List<CacheBehavior> behaviors = config.getCacheBehaviors();
        if (behaviors != null) {
            for (CacheBehavior behavior : behaviors) {
                if (pathPatternMatches(behavior.getPathPattern(), normalizedPath)) {
                    return behavior.getTargetOriginId();
                }
            }
        }
        DefaultCacheBehavior dflt = config.getDefaultCacheBehavior();
        return dflt != null ? dflt.getTargetOriginId() : null;
    }

    /**
     * Returns the trusted key group IDs of the cache behavior that matches {@code normalizedPath} (the
     * same ordered-first, default-last resolution as {@link #matchTargetOriginId}). An empty list means
     * the matched behavior serves public content — no request signature is required.
     */
    public static List<String> trustedKeyGroupsFor(DistributionConfig config, String normalizedPath) {
        List<CacheBehavior> behaviors = config.getCacheBehaviors();
        if (behaviors != null) {
            for (CacheBehavior behavior : behaviors) {
                if (pathPatternMatches(behavior.getPathPattern(), normalizedPath)) {
                    return behavior.getTrustedKeyGroups() != null ? behavior.getTrustedKeyGroups() : List.of();
                }
            }
        }
        DefaultCacheBehavior dflt = config.getDefaultCacheBehavior();
        if (dflt != null && dflt.getTrustedKeyGroups() != null) {
            return dflt.getTrustedKeyGroups();
        }
        return List.of();
    }

    /**
     * Matches a CloudFront path pattern against a normalized path. A leading {@code /} on either side is
     * ignored, {@code *} matches zero or more characters, {@code ?} matches exactly one, and matching is
     * case-sensitive. A {@code null}/blank or {@code *} pattern matches everything (the default behavior).
     */
    public static boolean pathPatternMatches(String pattern, String path) {
        if (pattern == null || pattern.isEmpty() || pattern.equals("*") || pattern.equals("/*")) {
            return true;
        }
        String p = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String candidate = path.startsWith("/") ? path.substring(1) : path;
        return Pattern.compile(wildcardToRegex(p)).matcher(candidate).matches();
    }

    private static String wildcardToRegex(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                // Escape every other regex metacharacter so patterns match literally.
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.toString();
    }

    /**
     * Computes the origin object key for a request: the default root object is used only for the root
     * request; the origin path is prepended; the result never begins with {@code /} (S3-object form).
     */
    public static String resolveOriginKey(String originPath, String normalizedPath, String defaultRootObject) {
        String combined = resolveForwardUri(originPath, normalizedPath, defaultRootObject);
        return combined.startsWith("/") ? combined.substring(1) : combined;
    }

    /**
     * Computes the URI to forward to the origin: the default root object replaces the path only for the
     * root request (applies to custom origins as well as S3), then the origin path is prepended. The
     * result keeps its leading {@code /} (custom-origin URI form).
     */
    public static String resolveForwardUri(String originPath, String normalizedPath, String defaultRootObject) {
        String object;
        if (isRoot(normalizedPath) && defaultRootObject != null && !defaultRootObject.isBlank()) {
            object = defaultRootObject;
        } else {
            object = normalizedPath;
        }
        return joinPath(originPath, object);
    }

    /** Joins an optional origin path with the object path, normalizing the slash between them. */
    public static String joinPath(String originPath, String object) {
        String base = (originPath == null) ? "" : originPath.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String obj = (object == null) ? "" : object;
        if (!obj.startsWith("/")) {
            obj = "/" + obj;
        }
        return base + obj;
    }

    /** Finds the origin with the given id, or {@code null}. */
    public static Origin findOrigin(DistributionConfig config, String originId) {
        if (config.getOrigins() == null || originId == null) {
            return null;
        }
        for (Origin origin : config.getOrigins()) {
            if (originId.equals(origin.getId())) {
                return origin;
            }
        }
        return null;
    }

    /** True when the origin is an S3 (non-website) origin — modeled by the presence of {@code S3OriginConfig}. */
    public static boolean isS3Origin(Origin origin) {
        return origin != null && origin.getS3OriginConfig() != null && origin.getCustomOriginConfig() == null;
    }

    /**
     * Extracts the bucket name from an S3 origin domain name. Handles the regional/global REST endpoints
     * ({@code bucket.s3.us-east-1.amazonaws.com}, {@code bucket.s3.amazonaws.com}) and the website
     * endpoint ({@code bucket.s3-website-us-east-1.amazonaws.com}) — the bucket is the leading label(s)
     * before the {@code .s3} / {@code .s3-website} marker.
     */
    public static String bucketFromS3Domain(String domainName) {
        if (domainName == null || domainName.isBlank()) {
            return null;
        }
        String host = domainName;
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        for (String marker : new String[] {".s3-website-", ".s3-website.", ".s3.", ".s3-"}) {
            int idx = host.indexOf(marker);
            if (idx > 0) {
                return host.substring(0, idx);
            }
        }
        // Fallback: a bare bucket label or an unrecognized form — take the first label.
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
