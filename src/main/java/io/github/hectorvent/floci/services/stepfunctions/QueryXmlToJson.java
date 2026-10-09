package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts an AWS Query-protocol XML response into the JSON shape that the Step Functions
 * {@code aws-sdk:*} service integration returns: the contents of the {@code <ActionResult>} element
 * as a JSON object, with {@code <member>} lists unwrapped into arrays and repeated elements
 * collapsed to arrays. When no {@code <ActionResult>} wrapper is present the document element's
 * children are converted (skipping {@code ResponseMetadata}/{@code requestId}).
 *
 * <p>A general DOM walk is used deliberately: the response shape varies per action (scalar,
 * nested object, member list), so element-by-element extraction is not sufficient.
 */
final class QueryXmlToJson {

    private QueryXmlToJson() {
    }

    static JsonNode convert(String xml, String resultElementName, ObjectMapper mapper) throws Exception {
        Document doc = parse(xml);
        Element wrapper = firstByLocalName(doc.getDocumentElement(), resultElementName);
        Element scope = wrapper != null ? wrapper : doc.getDocumentElement();
        ObjectNode obj = mapper.createObjectNode();
        for (Element child : childElements(scope)) {
            String name = localName(child);
            if (wrapper == null && (name.equals("ResponseMetadata") || name.equalsIgnoreCase("requestId"))) {
                continue;
            }
            merge(obj, name, toJson(child, mapper), mapper);
        }
        return obj;
    }

    private static JsonNode toJson(Element el, ObjectMapper mapper) {
        List<Element> kids = childElements(el);
        if (kids.isEmpty()) {
            return TextNode.valueOf(el.getTextContent());
        }
        boolean allMembers = kids.stream().allMatch(k -> "member".equals(localName(k)));
        if (allMembers) {
            ArrayNode arr = mapper.createArrayNode();
            for (Element k : kids) {
                arr.add(toJson(k, mapper));
            }
            return arr;
        }
        ObjectNode obj = mapper.createObjectNode();
        for (Element k : kids) {
            merge(obj, localName(k), toJson(k, mapper), mapper);
        }
        return obj;
    }

    private static void merge(ObjectNode obj, String name, JsonNode value, ObjectMapper mapper) {
        JsonNode existing = obj.get(name);
        if (existing == null) {
            obj.set(name, value);
            return;
        }
        if (existing.isArray()) {
            ((ArrayNode) existing).add(value);
            return;
        }
        ArrayNode arr = mapper.createArrayNode();
        arr.add(existing);
        arr.add(value);
        obj.set(name, arr);
    }

    private static Element firstByLocalName(Element root, String name) {
        if (localName(root).equals(name)) {
            return root;
        }
        NodeList nl = root.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && localName(e).equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element el) {
        List<Element> out = new ArrayList<>();
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private static String localName(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
