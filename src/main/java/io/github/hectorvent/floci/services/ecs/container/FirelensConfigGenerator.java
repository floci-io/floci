package io.github.hectorvent.floci.services.ecs.container;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates the Fluent Bit config FireLens would write for a task.
 *
 * <p>Shape is taken from amazon-ecs-agent {@code firelensconfig_unix.go}: unix-socket input,
 * optional TCP forward + healthcheck, ECS metadata {@code record_modifier}, optional
 * {@code @INCLUDE}, then one {@code [OUTPUT]} per {@code awsfirelens} container.
 */
final class FirelensConfigGenerator {

    static final String SOCKET_PATH = "/var/run/fluent.sock";
    static final int FORWARD_PORT = 24224;
    static final int HEALTHCHECK_PORT = 8877;

    private FirelensConfigGenerator() {
    }

    record Context(
            String networkMode,
            boolean ecsMetadataEnabled,
            String cluster,
            String taskArn,
            String taskDefinition,
            int routerMemoryMb,
            String externalConfigPath,
            Map<String, Map<String, String>> containerLogOptions
    ) {
    }

    static String fluentBitConfig(Context ctx) {
        StringBuilder out = new StringBuilder();
        appendInput(out, "forward", null, options(
                "unix_path", SOCKET_PATH,
                "Mem_Buf_Limit", memBufLimit(ctx.routerMemoryMb())));

        if (addsTcpForward(ctx.networkMode())) {
            appendInput(out, "forward", null, options(
                    "Listen", tcpListen(ctx.networkMode()),
                    "Port", String.valueOf(FORWARD_PORT)));
            appendInput(out, "tcp", "firelens-healthcheck", options(
                    "Listen", "127.0.0.1",
                    "Port", String.valueOf(HEALTHCHECK_PORT)));
        }

        if (ctx.containerLogOptions() != null) {
            for (Map.Entry<String, Map<String, String>> entry : ctx.containerLogOptions().entrySet()) {
                String tag = entry.getKey() + "-firelens*";
                Map<String, String> options = entry.getValue();
                if (options == null) {
                    continue;
                }
                String include = options.get("include-pattern");
                if (include != null) {
                    appendGrep(out, tag, "Regex", include);
                }
                String exclude = options.get("exclude-pattern");
                if (exclude != null) {
                    appendGrep(out, tag, "Exclude", exclude);
                }
            }
        }

        if (ctx.ecsMetadataEnabled()) {
            out.append("[FILTER]\n");
            out.append("    Name record_modifier\n");
            out.append("    Match *\n");
            appendRecord(out, "ecs_cluster", ctx.cluster());
            appendRecord(out, "ecs_task_arn", ctx.taskArn());
            appendRecord(out, "ecs_task_definition", ctx.taskDefinition());
            out.append('\n');
        }

        if (ctx.externalConfigPath() != null && !ctx.externalConfigPath().isBlank()) {
            out.append("@INCLUDE ").append(ctx.externalConfigPath()).append("\n\n");
        }

        if (addsTcpForward(ctx.networkMode())) {
            appendOutput(out, "null", "firelens-healthcheck", Map.of());
        }

        if (ctx.containerLogOptions() != null) {
            for (Map.Entry<String, Map<String, String>> entry : ctx.containerLogOptions().entrySet()) {
                Map<String, String> options = entry.getValue() == null ? Map.of() : entry.getValue();
                String plugin = options.get("Name");
                Map<String, String> pluginOptions = pluginOptions(options);
                if (plugin == null) {
                    if (!pluginOptions.isEmpty()) {
                        throw new IllegalArgumentException(
                                "missing output key Name which is required for firelens configuration of type fluentbit");
                    }
                    continue;
                }
                appendOutput(out, plugin, entry.getKey() + "-firelens*", pluginOptions);
            }
        }
        return out.toString();
    }

    static boolean addsTcpForward(String networkMode) {
        return "bridge".equals(networkMode) || "awsvpc".equals(networkMode);
    }

    static String tcpListen(String networkMode) {
        // AWS binds 127.0.0.1 in awsvpc because every container shares the task's network
        // namespace. Floci runs each container in its own namespace, so the router must listen
        // on all interfaces for the injected FLUENT_HOST to be reachable from sibling containers.
        return "0.0.0.0";
    }

    static String memBufLimit(int routerMemoryMb) {
        int limit = routerMemoryMb / 2;
        if (limit <= 0) {
            limit = 25;
        }
        return limit + "MB";
    }

    static Map<String, String> pluginOptions(Map<String, String> options) {
        LinkedHashMap<String, String> pluginOptions = new LinkedHashMap<>();
        for (Map.Entry<String, String> option : options.entrySet()) {
            String key = option.getKey();
            if ("Name".equals(key) || "include-pattern".equals(key)
                    || "exclude-pattern".equals(key) || "log-driver-buffer-limit".equals(key)) {
                continue;
            }
            pluginOptions.put(key, option.getValue());
        }
        return pluginOptions;
    }

    private static Map<String, String> options(String... keyValues) {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            options.put(keyValues[i], keyValues[i + 1]);
        }
        return options;
    }

    private static void appendInput(StringBuilder out, String name, String tag, Map<String, String> options) {
        out.append("[INPUT]\n");
        out.append("    Name ").append(name).append('\n');
        if (tag != null) {
            out.append("    Tag ").append(tag).append('\n');
        }
        for (Map.Entry<String, String> option : options.entrySet()) {
            out.append("    ").append(option.getKey()).append(' ').append(option.getValue()).append('\n');
        }
        out.append('\n');
    }

    private static void appendGrep(StringBuilder out, String tag, String kind, String pattern) {
        out.append("[FILTER]\n");
        out.append("    Name grep\n");
        out.append("    Match ").append(tag).append('\n');
        out.append("    ").append(kind).append(" log ").append(pattern).append('\n');
        out.append('\n');
    }

    private static void appendRecord(StringBuilder out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.append("    Record ").append(key).append(' ').append(value).append('\n');
        }
    }

    private static void appendOutput(StringBuilder out, String name, String tag, Map<String, String> options) {
        out.append("[OUTPUT]\n");
        out.append("    Name ").append(name).append('\n');
        out.append("    Match ").append(tag).append('\n');
        for (Map.Entry<String, String> option : options.entrySet()) {
            out.append("    ").append(option.getKey()).append(' ').append(option.getValue()).append('\n');
        }
        out.append('\n');
    }
}
