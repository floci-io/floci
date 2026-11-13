package io.floci.aws.services.lambda.durable;

import io.floci.aws.core.common.XRayTraceIds;

import java.util.regex.Pattern;

/** The X-Ray trace header of a durable execution and of each invocation of it. */
final class DurableTraceHeader {

    private static final Pattern ROOT = Pattern.compile("1-[0-9a-fA-F]{8}-[0-9a-fA-F]{24}");
    private static final Pattern PARENT = Pattern.compile("[0-9a-fA-F]{16}");

    private DurableTraceHeader() {
    }

    private record Fields(String root, String parent, String sampled) {
    }

    /**
     * Lambda records its own segment for an Active function that samples the request, so the Parent
     * it was sent is replaced.
     */
    static String forExecution(String received, boolean activeTracing, long epochSeconds) {
        Fields fields = parse(received);
        String root = fields.root() != null ? fields.root() : XRayTraceIds.traceId(epochSeconds);
        String sampled = fields.sampled() != null ? fields.sampled() : activeTracing ? "1" : "0";
        String parent;
        if (activeTracing && "1".equals(sampled)) {
            parent = XRayTraceIds.segmentId();
        } else if (fields.parent() != null) {
            parent = fields.parent();
        } else if (fields.sampled() == null) {
            parent = XRayTraceIds.segmentId();
        } else {
            parent = null;
        }
        return format(root, parent, sampled);
    }

    /**
     * Unlike Invoke, the Parent sent is kept as is, and an Active target gets none when none was sent.
     */
    static String forChainedChild(String requested, String callerHeader, boolean activeTracing) {
        Fields fields = parse(requested);
        if (fields.root() == null) {
            return callerHeader;
        }
        String sampled = fields.sampled() != null ? fields.sampled() : activeTracing ? "1" : "0";
        String parent = fields.parent();
        if (parent == null && !activeTracing && fields.sampled() == null) {
            parent = XRayTraceIds.segmentId();
        }
        return format(fields.root(), parent, sampled);
    }

    static String forInvocation(String executionHeader, boolean activeTracing) {
        if (executionHeader == null) {
            return null;
        }
        Fields fields = parse(executionHeader);
        if (activeTracing) {
            return format(fields.root(), XRayTraceIds.segmentId(), fields.sampled());
        }
        if (fields.parent() != null) {
            return executionHeader;
        }
        if ("0".equals(fields.sampled())) {
            return format(fields.root(), XRayTraceIds.segmentId(), "0");
        }
        return "Root=" + fields.root();
    }

    private static Fields parse(String header) {
        String root = null;
        String parent = null;
        String sampled = null;
        if (header != null) {
            for (String part : header.split(";")) {
                String field = part.trim();
                int equals = field.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = field.substring(0, equals);
                String value = field.substring(equals + 1);
                if ("Root".equals(key) && root == null && ROOT.matcher(value).matches()) {
                    root = value;
                } else if ("Parent".equals(key) && parent == null && PARENT.matcher(value).matches()) {
                    parent = value;
                } else if ("Sampled".equals(key) && sampled == null
                        && ("0".equals(value) || "1".equals(value))) {
                    sampled = value;
                }
            }
        }
        return new Fields(root, parent, sampled);
    }

    private static String format(String root, String parent, String sampled) {
        StringBuilder header = new StringBuilder("Root=").append(root);
        if (parent != null) {
            header.append(";Parent=").append(parent);
        }
        return header.append(";Sampled=").append(sampled).toString();
    }
}
