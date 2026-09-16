package io.github.hectorvent.floci.services.lambda.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed Docker options applied to every Lambda execution container. */
record LambdaDockerFlags(
        List<String> environment,
        List<String> volumes,
        List<String> publishedPorts,
        List<String> extraHosts,
        List<String> dnsServers,
        Map<String, String> labels,
        String network,
        String user,
        boolean privileged,
        String platform
) {

    static LambdaDockerFlags parse(String input) {
        List<String> tokens = tokenize(input);
        List<String> environment = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        List<String> publishedPorts = new ArrayList<>();
        List<String> extraHosts = new ArrayList<>();
        List<String> dnsServers = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        String network = null;
        String user = null;
        String platform = null;
        boolean privileged = false;

        for (int index = 0; index < tokens.size(); index++) {
            String flag = tokens.get(index);
            switch (flag) {
                case "-e", "--env" -> environment.add(value(tokens, ++index, flag));
                case "-v", "--volume" -> volumes.add(value(tokens, ++index, flag));
                case "-p", "--publish" -> publishedPorts.add(value(tokens, ++index, flag));
                case "--add-host" -> extraHosts.add(value(tokens, ++index, flag));
                case "--dns" -> dnsServers.add(value(tokens, ++index, flag));
                case "--label" -> addKeyValue(labels, value(tokens, ++index, flag), flag);
                case "--network" -> network = value(tokens, ++index, flag);
                case "-u", "--user" -> user = value(tokens, ++index, flag);
                case "--privileged" -> privileged = true;
                case "--platform" -> platform = value(tokens, ++index, flag);
                case "" -> { }
                default -> throw new IllegalArgumentException("Unsupported Lambda Docker flag: " + flag);
            }
        }
        return new LambdaDockerFlags(List.copyOf(environment), List.copyOf(volumes),
                List.copyOf(publishedPorts), List.copyOf(extraHosts), List.copyOf(dnsServers),
                Map.copyOf(labels), network, user, privileged, platform);
    }

    private static String value(List<String> tokens, int index, String flag) {
        if (index >= tokens.size() || tokens.get(index).isBlank() || tokens.get(index).startsWith("-")) {
            throw new IllegalArgumentException("Lambda Docker flag requires a value: " + flag);
        }
        return tokens.get(index);
    }

    private static void addKeyValue(Map<String, String> values, String token, String flag) {
        int separator = token.indexOf('=');
        if (separator <= 0) {
            throw new IllegalArgumentException("Lambda Docker flag requires KEY=VALUE: " + flag);
        }
        values.put(token.substring(0, separator), token.substring(separator + 1));
    }

    static List<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char character : input.toCharArray()) {
            if (escaped) {
                token.append(character);
                escaped = false;
            } else if (character == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    token.append(character);
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (escaped || quote != 0) {
            throw new IllegalArgumentException("Unterminated quote or escape in Lambda Docker flags");
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }
}
