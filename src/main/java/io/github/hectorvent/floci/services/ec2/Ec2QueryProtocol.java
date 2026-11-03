package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The EC2 Query protocol's parameter and response shapes, shared by the handlers that serve EC2
 * actions: numbered members ({@code Name.1}, {@code Name.2}), {@code Filter.N} entries, optional
 * booleans and integers, the {@code tagSet} element and the response envelope.
 */
final class Ec2QueryProtocol {

    private Ec2QueryProtocol() {
    }

    /** The values of {@code prefix.1}, {@code prefix.2}, ... up to the first one missing. */
    static List<String> getList(MultivaluedMap<String, String> p, String prefix) {
        List<String> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String v = p.getFirst(prefix + "." + i);
            if (v == null) {
                break;
            }
            result.add(v);
        }
        return result;
    }

    /** The numbered values of each prefix in turn. */
    static List<String> getList(MultivaluedMap<String, String> p, String... prefixes) {
        List<String> result = new ArrayList<>();
        for (String prefix : prefixes) {
            result.addAll(getList(p, prefix));
        }
        return result;
    }

    /**
     * Returns filters as a list of entries to preserve multiple filters with the same name
     * (AND semantics across filters, OR within values of the same filter).
     */
    static List<Map.Entry<String, List<String>>> getFiltersList(MultivaluedMap<String, String> p) {
        List<Map.Entry<String, List<String>>> filters = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = p.getFirst("Filter." + i + ".Name");
            if (name == null) {
                break;
            }
            filters.add(new AbstractMap.SimpleEntry<>(name, getList(p, "Filter." + i + ".Value")));
        }
        return filters;
    }

    /** The boolean member, or null when it is absent or blank. */
    static Boolean boolParam(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        return isSet(value) ? Boolean.valueOf(Boolean.parseBoolean(value)) : null;
    }

    /** The integer member, or null when it is absent or blank; anything else is refused. */
    static Integer intParam(MultivaluedMap<String, String> p, String name) {
        String value = p.getFirst(name);
        if (!isSet(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " is not a valid integer.", 400);
        }
    }

    /** A resource's tags as the {@code tagSet} element, {@code <tagSet/>} when it has none. */
    static String tagSetXml(List<Tag> tagList) {
        if (tagList == null || tagList.isEmpty()) {
            return "<tagSet/>";
        }
        XmlBuilder xml = new XmlBuilder().start("tagSet");
        for (Tag tag : tagList) {
            xml.start("item")
                    .elem("key", tag.getKey())
                    .elem("value", tag.getValue())
                    .end("item");
        }
        xml.end("tagSet");
        return xml.build();
    }

    /** {@code <ActionResponse>} opened in the EC2 namespace, with its request id. */
    static XmlBuilder startResponse(String action) {
        return new XmlBuilder().start(action + "Response", AwsNamespaces.EC2)
                .elem("requestId", UUID.randomUUID().toString());
    }

    /** Closes the response {@link #startResponse} opened and answers with it. */
    static Response endResponse(XmlBuilder xml, String action) {
        return xmlResponse(xml.end(action + "Response").build());
    }

    static Response xmlResponse(String xml) {
        return Response.ok(xml).type(MediaType.APPLICATION_XML).build();
    }

    /** The response of an action that answers only {@code <return>true</return>}. */
    static Response booleanResponse(String action) {
        return endResponse(startResponse(action).elem("return", "true"), action);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
