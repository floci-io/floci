package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.message.DefaultMessageBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parser for detecting EKS cluster join requests from EC2 instance user data payloads.
 */
public final class EksBootstrapUserData {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final Set<String> FLAGS_WITH_ARG = Set.of(
            "--b64-cluster-ca", "--apiserver-endpoint", "--kubelet-extra-args",
            "--container-runtime", "--dns-cluster-ip", "--ip-family",
            "--service-ipv6-cidr", "--use-max-pods", "--aws-api-retry-attempts");

    private EksBootstrapUserData() {
    }

    /**
     * Extracts an EKS cluster name from an instance user data string if present.
     * Supports Amazon Linux 2 bootstrap scripts and Amazon Linux 2023 nodeadm NodeConfig YAML or MIME multipart.
     *
     * @param userData the decoded user data payload
     * @return the cluster name if found, or empty otherwise
     */
    public static Optional<String> extractClusterName(String userData) {
        if (userData == null || userData.isBlank()) {
            return Optional.empty();
        }
        return extractFromBootstrapScript(userData).or(() -> extractFromNodeadm(userData));
    }

    private static Optional<String> extractFromBootstrapScript(String userData) {
        for (String line : userData.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") && !trimmed.startsWith("#!")) {
                continue;
            }
            int idx = trimmed.indexOf("bootstrap.sh");
            if (idx >= 0) {
                List<String> tokens = tokenize(trimmed.substring(idx + "bootstrap.sh".length()).trim());
                for (int i = 0; i < tokens.size(); i++) {
                    String tok = tokens.get(i);
                    if (tok.startsWith("--") && !tok.contains("=") && FLAGS_WITH_ARG.contains(tok) && i + 1 < tokens.size()) {
                        i++;
                    } else if (!tok.isEmpty()) {
                        String name = stripQuotes(tok);
                        if (!name.isEmpty() && !name.startsWith("-")) {
                            return Optional.of(name);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractFromNodeadm(String userData) {
        return nodeConfigs(userData).stream()
                .map(root -> root.path("spec").path("cluster").path("name").asText().strip())
                .filter(name -> !name.isEmpty())
                .findFirst();
    }

    /**
     * The labels a nodeadm {@code NodeConfig} in the user data gives the node through its kubelet's
     * {@code --node-labels} flag, as a kubelet registers them; empty without one.
     */
    public static Map<String, String> nodeLabels(String userData) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String value : kubeletFlag(userData, "--node-labels")) {
            for (String pair : value.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    labels.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
                }
            }
        }
        return labels;
    }

    /**
     * The taints a nodeadm {@code NodeConfig} in the user data registers the node with through its kubelet's
     * {@code --register-with-taints} flag ({@code key[=value]:Effect}), as {@code key}, {@code value} and
     * {@code effect}; empty without one.
     */
    public static List<Map<String, String>> registerWithTaints(String userData) {
        List<Map<String, String>> taints = new ArrayList<>();
        for (String value : kubeletFlag(userData, "--register-with-taints")) {
            for (String spec : value.split(",")) {
                int colon = spec.lastIndexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String keyValue = spec.substring(0, colon).strip();
                int eq = keyValue.indexOf('=');
                Map<String, String> taint = new LinkedHashMap<>();
                taint.put("key", eq < 0 ? keyValue : keyValue.substring(0, eq));
                taint.put("value", eq < 0 ? "" : keyValue.substring(eq + 1));
                taint.put("effect", spec.substring(colon + 1).strip());
                taints.add(taint);
            }
        }
        return taints;
    }

    /** The values of a kubelet flag in the {@code spec.kubelet.flags} of every NodeConfig in the user data. */
    private static List<String> kubeletFlag(String userData, String flag) {
        List<String> values = new ArrayList<>();
        if (userData == null || userData.isBlank()) {
            return values;
        }
        for (JsonNode root : nodeConfigs(userData)) {
            for (JsonNode entry : root.path("spec").path("kubelet").path("flags")) {
                String text = entry.asText().strip();
                if (text.startsWith(flag + "=")) {
                    values.add(stripQuotes(text.substring(flag.length() + 1)));
                }
            }
        }
        return values;
    }

    /** Every nodeadm {@code NodeConfig} document in the user data, plain YAML or inside MIME multipart parts. */
    private static List<JsonNode> nodeConfigs(String userData) {
        if (!userData.contains("NodeConfig") && !userData.contains("node.eks.aws")) {
            return List.of();
        }
        List<JsonNode> found = new ArrayList<>();
        if (userData.contains("Content-Type:") || userData.contains("MIME-Version:") || userData.contains("multipart/")) {
            try {
                Message message = new DefaultMessageBuilder().parseMessage(
                        new ByteArrayInputStream(userData.getBytes(StandardCharsets.UTF_8)));
                collectNodeConfigs(message, found);
            } catch (Exception ignored) {
                // Malformed MIME structure in user data is treated as non-multipart content.
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        parseNodeConfigYaml(userData, found);
        return found;
    }

    private static void collectNodeConfigs(Entity entity, List<JsonNode> found) {
        if (entity == null) {
            return;
        }
        if (entity.getBody() instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                collectNodeConfigs(part, found);
            }
        } else if (entity.getBody() instanceof SingleBody singleBody) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                singleBody.writeTo(out);
                String content = out.toString(StandardCharsets.UTF_8);
                if (content.contains("NodeConfig") || (entity.getMimeType() != null && entity.getMimeType().contains("node.eks.aws"))) {
                    parseNodeConfigYaml(content, found);
                }
            } catch (Exception ignored) {
                // Ignore unreadable or malformed MIME body parts and continue scanning.
            }
        }
    }

    private static void parseNodeConfigYaml(String yaml, List<JsonNode> found) {
        try {
            Iterator<JsonNode> it = YAML_MAPPER.readValues(
                    YAML_MAPPER.getFactory().createParser(yaml), JsonNode.class);
            while (it.hasNext()) {
                JsonNode root = it.next();
                if (root != null && "NodeConfig".equals(root.path("kind").asText())) {
                    found.add(root);
                }
            }
        } catch (Exception ignored) {
            // Non-YAML or invalid NodeConfig user data is safely ignored.
        }
    }

    private static String stripQuotes(String s) {
        if (s == null) {
            return "";
        }
        String res = s.trim();
        boolean quoted = (res.startsWith("\"") && res.endsWith("\"")) || (res.startsWith("'") && res.endsWith("'"));
        return (quoted && res.length() >= 2) ? res.substring(1, res.length() - 1).trim() : res;
    }

    private static List<String> tokenize(String cmd) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char q = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (inQuotes) {
                if (c == q) {
                    inQuotes = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                inQuotes = true;
                q = c;
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    list.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            list.add(cur.toString());
        }
        return list;
    }
}
