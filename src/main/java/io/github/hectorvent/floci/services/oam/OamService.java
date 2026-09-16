package io.github.hectorvent.floci.services.oam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.oam.model.OamLink;
import io.github.hectorvent.floci.services.oam.model.OamSink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class OamService {
    private static final Pattern SINK_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,255}");
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "AWS::CloudWatch::Metric", "AWS::Logs::LogGroup", "AWS::XRay::Trace",
            "AWS::ApplicationInsights::Application", "AWS::InternetMonitor::Monitor",
            "AWS::ApplicationSignals::Service", "AWS::ApplicationSignals::ServiceLevelObjective");

    private final StorageBackend<String, OamSink> sinks;
    private final StorageBackend<String, OamLink> links;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OamService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("oam", "oam-sinks.json", new TypeReference<Map<String, OamSink>>() {}),
                storageFactory.create("oam", "oam-links.json", new TypeReference<Map<String, OamLink>>() {}),
                regionResolver, objectMapper);
    }

    OamService(StorageBackend<String, OamSink> sinks, StorageBackend<String, OamLink> links,
               RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.sinks = sinks;
        this.links = links;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public OamSink createSink(String name, Map<String, String> tags, String region) {
        if (name == null) throw missing("Name");
        if (!SINK_NAME.matcher(name).matches()) throw invalid("Name is invalid.");
        validateTags(tags);
        if (!listSinks(region).isEmpty()) {
            throw new AwsException("ConflictException", "A sink already exists in this Region.", 409);
        }
        String id = UUID.randomUUID().toString();
        OamSink sink = new OamSink();
        sink.setId(id);
        sink.setName(name);
        sink.setRegion(region);
        sink.setOwnerAccountId(regionResolver.getAccountId());
        sink.setArn(AwsArnUtils.Arn.of("oam", region, sink.getOwnerAccountId(), "sink/" + id).toString());
        sink.setTags(tags);
        sinks.put(sink.getArn(), sink);
        return sink;
    }

    public OamSink getSink(String identifier) {
        require(identifier, "Identifier");
        return getSinkAcrossAccounts(identifier);
    }

    public List<OamSink> listSinks(String region) {
        return sinks.scan(_ -> true).stream()
                .filter(s -> region.equals(s.getRegion()))
                .sorted(java.util.Comparator.comparing(OamSink::getArn)).toList();
    }

    public void putSinkPolicy(String sinkIdentifier, String policy) {
        require(sinkIdentifier, "SinkIdentifier");
        require(policy, "Policy");
        try {
            JsonNode document = objectMapper.readTree(policy);
            if (!document.isObject() || !document.has("Statement")) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw invalid("Policy must be valid IAM policy JSON.");
        }
        OamSink sink = getOwnedSink(sinkIdentifier);
        sink.setPolicy(policy);
        sinks.put(sink.getArn(), sink);
    }

    public OamLink createLink(String sinkIdentifier, String labelTemplate, List<String> resourceTypes,
                              Map<String, Object> linkConfiguration, Map<String, String> tags, String region) {
        require(sinkIdentifier, "SinkIdentifier");
        require(labelTemplate, "LabelTemplate");
        if (labelTemplate.length() > 64) throw invalid("LabelTemplate must be between 1 and 64 characters.");
        validateResourceTypes(resourceTypes);
        validateLinkConfiguration(linkConfiguration);
        validateTags(tags);
        if (listLinks(region).size() >= 5) {
            throw new AwsException("ServiceQuotaExceededException", "A source account can have at most five links.", 429);
        }
        OamSink sink = getSinkAcrossAccounts(sinkIdentifier);
        if (!region.equals(sink.getRegion())) throw invalid("The sink must be in the same Region as the link.");
        authorizeLinkAction(sink, regionResolver.getAccountId(), resourceTypes, "oam:CreateLink");
        boolean duplicate = listLinks(region).stream().anyMatch(l -> sink.getArn().equals(l.getSinkArn()));
        if (duplicate) throw new AwsException("ConflictException", "A link to this sink already exists.", 409);

        String id = UUID.randomUUID().toString();
        OamLink link = new OamLink();
        link.setId(id);
        link.setArn(AwsArnUtils.Arn.of("oam", region, regionResolver.getAccountId(), "link/" + id).toString());
        link.setSourceAccountId(regionResolver.getAccountId());
        link.setRegion(region);
        link.setSinkArn(sink.getArn());
        link.setLabelTemplate(labelTemplate);
        link.setLabel(resolveLabel(labelTemplate, regionResolver.getAccountId()));
        link.setResourceTypes(resourceTypes);
        link.setLinkConfiguration(normalizeConfiguration(linkConfiguration));
        link.setTags(tags);
        links.put(link.getArn(), link);
        return link;
    }

    public OamLink getLink(String identifier) {
        require(identifier, "Identifier");
        return links.get(identifier).orElseThrow(() -> notFound("Link", identifier));
    }

    public List<OamLink> listLinks(String region) {
        return links.scan(_ -> true).stream().filter(l -> region.equals(l.getRegion()))
                .sorted(java.util.Comparator.comparing(OamLink::getArn)).toList();
    }

    public List<OamLink> listAttachedLinks(String sinkIdentifier) {
        OamSink sink = getOwnedSink(sinkIdentifier);
        Collection<OamLink> all;
        if (links instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<OamLink> typed = (AccountAwareStorageBackend<OamLink>) aware;
            all = typed.scanAllAccounts();
        } else {
            all = links.scan(_ -> true);
        }
        return all.stream().filter(l -> sink.getArn().equals(l.getSinkArn()))
                .sorted(java.util.Comparator.comparing(OamLink::getArn)).toList();
    }

    public OamLink updateLink(String identifier, List<String> resourceTypes,
                              Map<String, Object> linkConfiguration) {
        validateResourceTypes(resourceTypes);
        validateLinkConfiguration(linkConfiguration);
        OamLink link = getLink(identifier);
        OamSink sink = getSinkAcrossAccounts(link.getSinkArn());
        authorizeLinkAction(sink, regionResolver.getAccountId(), resourceTypes, "oam:UpdateLink");
        link.setResourceTypes(resourceTypes);
        link.setLinkConfiguration(normalizeConfiguration(linkConfiguration));
        links.put(link.getArn(), link);
        return link;
    }

    public void deleteLink(String identifier) {
        OamLink link = getLink(identifier);
        links.delete(link.getArn());
    }

    public void deleteSink(String identifier) {
        OamSink sink = getOwnedSink(identifier);
        if (!listAttachedLinks(sink.getArn()).isEmpty()) {
            throw new AwsException("ConflictException", "The sink still has attached links.", 409);
        }
        sinks.delete(sink.getArn());
    }

    public Map<String, String> tags(String arn) { return new LinkedHashMap<>(resourceTags(arn)); }

    public void tag(String arn, Map<String, String> tags) {
        validateTags(tags);
        Map<String, String> merged = new LinkedHashMap<>(resourceTags(arn));
        merged.putAll(tags == null ? Map.of() : tags);
        if (merged.size() > 50) throw new AwsException("TooManyTagsException", "A resource can have no more than 50 tags.", 400);
        saveTags(arn, merged);
    }

    public void untag(String arn, List<String> tagKeys) {
        if (tagKeys == null) throw missing("TagKeys");
        Map<String, String> updated = new LinkedHashMap<>(resourceTags(arn));
        tagKeys.forEach(updated::remove);
        saveTags(arn, updated);
    }

    private OamSink getOwnedSink(String identifier) {
        OamSink sink = sinks.get(identifier).orElseThrow(() -> notFound("Sink", identifier));
        if (!regionResolver.getAccountId().equals(sink.getOwnerAccountId())) throw notFound("Sink", identifier);
        return sink;
    }

    private OamSink getSinkAcrossAccounts(String arn) {
        String owner = arnAccount(arn);
        if (owner == null) throw invalid("SinkIdentifier must be a sink ARN.");
        if (sinks instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<OamSink> typed = (AccountAwareStorageBackend<OamSink>) aware;
            return typed.getForAccount(owner, arn).orElseThrow(() -> notFound("Sink", arn));
        }
        return sinks.get(arn).orElseThrow(() -> notFound("Sink", arn));
    }

    private Map<String, String> resourceTags(String arn) {
        if (arn != null && arn.contains(":sink/")) return getOwnedSink(arn).getTags();
        if (arn != null && arn.contains(":link/")) return getLink(arn).getTags();
        throw notFound("Resource", arn);
    }

    private void saveTags(String arn, Map<String, String> tags) {
        if (arn != null && arn.contains(":sink/")) {
            OamSink sink = getOwnedSink(arn); sink.setTags(tags); sinks.put(arn, sink); return;
        }
        if (arn != null && arn.contains(":link/")) {
            OamLink link = getLink(arn); link.setTags(tags); links.put(arn, link); return;
        }
        throw notFound("Resource", arn);
    }

    private void authorizeLinkAction(OamSink sink, String sourceAccountId, List<String> resourceTypes, String action) {
        if (sink.getPolicy() == null || sink.getPolicy().isBlank()) {
            throw invalid("The sink policy does not allow this source account to perform " + action + ".");
        }
        try {
            JsonNode root = objectMapper.readTree(sink.getPolicy());
            JsonNode statements = root.path("Statement");
            if (!statements.isArray()) statements = objectMapper.createArrayNode().add(statements);
            boolean allowed = false;
            for (JsonNode statement : statements) {
                if (!actionMatches(statement.get("Action"), action)) continue;
                if (!principalMatches(statement.get("Principal"), sourceAccountId)) continue;
                if (!resourceMatches(statement.get("Resource"), sink.getArn())) continue;
                if (!resourceTypeConditionMatches(statement.get("Condition"), resourceTypes)) continue;

                String effect = statement.path("Effect").asText();
                if ("Deny".equalsIgnoreCase(effect)) {
                    throw invalid("The sink policy explicitly denies this source account from performing " + action + ".");
                }
                if ("Allow".equalsIgnoreCase(effect)) {
                    allowed = true;
                }
            }
            if (!allowed) throw invalid("The sink policy does not allow this source account to perform " + action + ".");
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("The sink policy could not be evaluated.");
        }
    }

    private static boolean actionMatches(JsonNode node, String action) {
        if (node == null) return false;
        if (node.isTextual()) return "*".equals(node.asText()) || action.equalsIgnoreCase(node.asText());
        if (node.isArray()) for (JsonNode n : node) if (actionMatches(n, action)) return true;
        return false;
    }

    private static boolean principalMatches(JsonNode node, String account) {
        if (node == null) return false;
        if (node.isTextual()) return "*".equals(node.asText()) || principalValueMatches(node.asText(), account);
        JsonNode aws = node.path("AWS");
        if (aws.isTextual()) return principalValueMatches(aws.asText(), account);
        if (aws.isArray()) for (JsonNode n : aws) if (principalValueMatches(n.asText(), account)) return true;
        return false;
    }

    private static boolean principalValueMatches(String value, String account) {
        return "*".equals(value) || account.equals(value) || ("arn:aws:iam::" + account + ":root").equals(value);
    }

    private static boolean resourceMatches(JsonNode node, String arn) {
        if (node == null) return true;
        if (node.isTextual()) return "*".equals(node.asText()) || arn.equals(node.asText());
        if (node.isArray()) for (JsonNode n : node) if (resourceMatches(n, arn)) return true;
        return false;
    }

    private static boolean resourceTypeConditionMatches(JsonNode condition, List<String> requested) {
        if (condition == null || condition.isMissingNode() || condition.isNull()) {
            return true;
        }
        List<String> allowed = new ArrayList<>();
        var operators = condition.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operator = operators.next();
            JsonNode entries = operator.getValue();
            if (!entries.isObject()) {
                return false;
            }
            var conditions = entries.fields();
            while (conditions.hasNext()) {
                Map.Entry<String, JsonNode> entry = conditions.next();
                if (!"oam:ResourceTypes".equalsIgnoreCase(entry.getKey())) {
                    // Unknown condition keys cannot be safely treated as satisfied. In particular,
                    // organization-scoped policies must not degrade into a wildcard allow.
                    return false;
                }
                JsonNode values = entry.getValue();
                if (values.isArray()) {
                    values.forEach(v -> allowed.add(v.asText()));
                } else {
                    allowed.add(values.asText());
                }
            }
        }
        return allowed.isEmpty() || allowed.containsAll(requested);
    }

    private static void validateResourceTypes(List<String> types) {
        if (types == null) throw missing("ResourceTypes");
        if (types.isEmpty() || types.size() > 50 || types.stream().anyMatch(t -> !RESOURCE_TYPES.contains(t))) {
            throw invalid("ResourceTypes contains an unsupported value.");
        }
    }

    private static void validateLinkConfiguration(Map<String, Object> config) {
        if (config == null) return;
        for (String name : List.of("MetricConfiguration", "LogGroupConfiguration")) {
            Object value = config.get(name);
            if (value instanceof Map<?, ?> m && m.containsKey("Filter")) {
                String filter = String.valueOf(m.get("Filter"));
                if (filter.isBlank() || filter.length() > 2000) throw invalid("LinkConfiguration filter is invalid.");
            }
        }
    }

    private static Map<String, Object> normalizeConfiguration(Map<String, Object> config) {
        if (config == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(config);
        out.entrySet().removeIf(e -> e.getValue() instanceof Map<?, ?> m && "*".equals(String.valueOf(m.get("Filter"))));
        return out;
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags == null) return;
        if (tags.size() > 50) throw new AwsException("TooManyTagsException", "A resource can have no more than 50 tags.", 400);
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getKey().length() > 128
                    || (e.getValue() != null && e.getValue().length() > 256)) {
                throw new AwsException("ValidationException", "Tag key or value is invalid.", 400);
            }
        }
    }

    private static String resolveLabel(String template, String account) {
        return template.replace("$AccountName", account).replace("$AccountEmailNoDomain", account)
                .replace("$AccountEmail", account);
    }

    private static String arnAccount(String arn) {
        try { return AwsArnUtils.parse(arn).accountId(); } catch (Exception e) { return null; }
    }

    public static String encodeToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    public static int decodeToken(String token) {
        if (token == null || token.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0) throw new NumberFormatException();
            return offset;
        } catch (Exception e) {
            throw invalid("NextToken is invalid.");
        }
    }

    private static void require(String value, String field) { if (value == null || value.isBlank()) throw missing(field); }
    private static AwsException missing(String field) { return new AwsException("MissingRequiredParameterException", "Missing required parameter: " + field, 400); }
    private static AwsException invalid(String message) { return new AwsException("InvalidParameterException", message, 400); }
    private static AwsException notFound(String type, String id) { return new AwsException("ResourceNotFoundException", type + " " + id + " not found.", 404); }
}
