package io.github.hectorvent.floci.services.eks;

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
import java.util.List;
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
        if (!userData.contains("NodeConfig") && !userData.contains("node.eks.aws")) {
            return Optional.empty();
        }
        if (userData.contains("Content-Type:") || userData.contains("MIME-Version:") || userData.contains("multipart/")) {
            Optional<String> mimeExtracted = extractFromMime(userData);
            if (mimeExtracted.isPresent()) {
                return mimeExtracted;
            }
        }
        return parseNodeConfigYaml(userData);
    }

    private static Optional<String> extractFromMime(String userData) {
        try {
            Message message = new DefaultMessageBuilder().parseMessage(
                    new ByteArrayInputStream(userData.getBytes(StandardCharsets.UTF_8)));
            return extractNodeConfigFromEntity(message);
        } catch (Exception ignored) {
            // Malformed MIME structure in user data is treated as non-multipart content.
            return Optional.empty();
        }
    }

    private static Optional<String> extractNodeConfigFromEntity(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        if (entity.getBody() instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                Optional<String> found = extractNodeConfigFromEntity(part);
                if (found.isPresent()) {
                    return found;
                }
            }
        } else if (entity.getBody() instanceof SingleBody singleBody) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                singleBody.writeTo(out);
                String content = out.toString(StandardCharsets.UTF_8);
                if (content.contains("NodeConfig") || (entity.getMimeType() != null && entity.getMimeType().contains("node.eks.aws"))) {
                    return parseNodeConfigYaml(content);
                }
            } catch (Exception ignored) {
                // Ignore unreadable or malformed MIME body parts and continue scanning.
            }
        }
        return Optional.empty();
    }

    private static Optional<String> parseNodeConfigYaml(String yaml) {
        try {
            Iterator<JsonNode> it = YAML_MAPPER.readValues(
                    YAML_MAPPER.getFactory().createParser(yaml), JsonNode.class);
            while (it.hasNext()) {
                JsonNode root = it.next();
                if (root != null && "NodeConfig".equals(root.path("kind").asText())) {
                    String name = root.path("spec").path("cluster").path("name").asText();
                    if (name != null && !name.isBlank()) {
                        return Optional.of(name.strip());
                    }
                }
            }
        } catch (Exception ignored) {
            // Non-YAML or invalid NodeConfig user data is safely ignored.
        }
        return Optional.empty();
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
