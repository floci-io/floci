package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedFieldSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared JSON parsing for basic and advanced CloudTrail event selectors. Used by the
 * {@code AWS::CloudTrail::Trail} CFN provisioner so it accepts the same shapes the
 * PutEventSelectors control-plane action does.
 */
public final class CloudTrailSelectorJson {

    private CloudTrailSelectorJson() {
    }

    public static List<EventSelector> parseEventSelectors(JsonNode selectorsNode) {
        List<EventSelector> result = new ArrayList<>();
        if (selectorsNode == null || !selectorsNode.isArray()) {
            return result;
        }
        for (JsonNode sel : selectorsNode) {
            String readWriteType = sel.has("ReadWriteType") ? sel.path("ReadWriteType").asText() : "All";
            Boolean includeManagement = sel.has("IncludeManagementEvents")
                    ? sel.path("IncludeManagementEvents").asBoolean() : null;
            List<String> excludeManagement = extractStringList(sel, "ExcludeManagementEventSources");
            List<DataResource> dataResources = new ArrayList<>();
            if (sel.has("DataResources")) {
                for (JsonNode dr : sel.path("DataResources")) {
                    String type = dr.path("Type").asText(null);
                    List<String> values = extractStringList(dr, "Values");
                    dataResources.add(new DataResource(type, values));
                }
            }
            result.add(new EventSelector(readWriteType, includeManagement, dataResources,
                    excludeManagement.isEmpty() ? null : excludeManagement));
        }
        return result;
    }

    public static List<AdvancedEventSelector> parseAdvancedEventSelectors(JsonNode selectorsNode) {
        List<AdvancedEventSelector> result = new ArrayList<>();
        if (selectorsNode == null || !selectorsNode.isArray()) {
            return result;
        }
        for (JsonNode sel : selectorsNode) {
            String name = sel.has("Name") ? sel.path("Name").asText(null) : null;
            List<AdvancedFieldSelector> fieldSelectors = new ArrayList<>();
            if (sel.has("FieldSelectors")) {
                for (JsonNode fs : sel.path("FieldSelectors")) {
                    fieldSelectors.add(new AdvancedFieldSelector(
                            fs.path("Field").asText(null),
                            extractStringList(fs, "Equals"),
                            extractStringList(fs, "NotEquals"),
                            extractStringList(fs, "StartsWith"),
                            extractStringList(fs, "NotStartsWith"),
                            extractStringList(fs, "EndsWith"),
                            extractStringList(fs, "NotEndsWith")));
                }
            }
            result.add(new AdvancedEventSelector(name, fieldSelectors));
        }
        return result;
    }

    private static List<String> extractStringList(JsonNode node, String fieldName) {
        List<String> result = new ArrayList<>();
        if (node != null && node.has(fieldName)) {
            node.path(fieldName).forEach(n -> result.add(n.asText()));
        }
        return result;
    }
}
