package io.github.hectorvent.floci.services.cloudfront;

import org.jboss.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * StAX-backed XML tree for CloudFront request bodies.
 *
 * <p>CloudFront reuses element names at many depths: {@code <Enabled>} appears on
 * DistributionConfig, on TrustedSigners, on TrustedKeyGroups and on Logging;
 * {@code <Quantity>}, {@code <Items>} and {@code <Id>} appear in a dozen places.
 * Flat helpers such as {@code XmlParser.extractFirst} return the first match in
 * document order, which picks up a nested structure's value instead of the one
 * being read. Parsing the body once into a tree and walking it by name keeps every
 * scalar bound to the structure that owns it.
 *
 * <p>Namespace prefixes are ignored: nodes are keyed by local name.
 */
final class CloudFrontXml {

    private static final Logger LOG = Logger.getLogger(CloudFrontXml.class);

    private static final XMLInputFactory FACTORY;

    static {
        FACTORY = XMLInputFactory.newInstance();
        FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private CloudFrontXml() {}

    /**
     * Parses {@code xml} into a tree. Malformed or empty input yields an empty node so
     * callers can walk the result unconditionally, matching {@code XmlParser}'s
     * lenient behavior.
     */
    static Node parse(String xml) {
        if (xml == null || xml.isEmpty()) {
            return Node.EMPTY;
        }
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            Node root = null;
            List<Node> stack = new ArrayList<>();
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    Node node = new Node(r.getLocalName());
                    if (stack.isEmpty()) {
                        root = node;
                    } else {
                        stack.get(stack.size() - 1).children.add(node);
                    }
                    stack.add(node);
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (!stack.isEmpty()) {
                        stack.get(stack.size() - 1).text.append(r.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!stack.isEmpty()) {
                        stack.remove(stack.size() - 1);
                    }
                }
            }
            r.close();
            return root != null ? root : Node.EMPTY;
        } catch (Exception e) {
            LOG.debugv("Ignoring malformed CloudFront XML: {0}", e.getMessage());
            return Node.EMPTY;
        }
    }

    /** One element of a parsed document. */
    static final class Node {

        static final Node EMPTY = new Node("");

        private final String name;
        private final StringBuilder text = new StringBuilder();
        private final List<Node> children = new ArrayList<>();

        private Node(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        /** Text content of this element, trimmed. Empty for container elements. */
        String text() {
            return text.toString().trim();
        }

        /** First direct child with the given name, or {@code null}. */
        Node child(String childName) {
            for (Node c : children) {
                if (c.name.equals(childName)) {
                    return c;
                }
            }
            return null;
        }

        /** All direct children with the given name. */
        List<Node> children(String childName) {
            List<Node> result = new ArrayList<>();
            for (Node c : children) {
                if (c.name.equals(childName)) {
                    result.add(c);
                }
            }
            return result;
        }

        /** Walks a chain of direct children, returning {@code null} if any step is missing. */
        Node path(String... names) {
            Node current = this;
            for (String n : names) {
                current = current.child(n);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }

        /** Direct children of this element, in document order. */
        List<Node> childNodes() {
            return Collections.unmodifiableList(children);
        }

        /** Text of a direct child, or {@code defaultValue} when the child is absent. */
        String text(String childName, String defaultValue) {
            Node c = child(childName);
            return c != null ? c.text() : defaultValue;
        }

        boolean bool(String childName, boolean defaultValue) {
            Node c = child(childName);
            if (c == null || c.text().isEmpty()) {
                return defaultValue;
            }
            return "true".equalsIgnoreCase(c.text());
        }

        int integer(String childName, int defaultValue) {
            Node c = child(childName);
            if (c == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(c.text());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        /** Long value of a direct child, or {@code null} when absent or unparseable. */
        Long longOrNull(String childName) {
            Node c = child(childName);
            if (c == null) {
                return null;
            }
            try {
                return Long.parseLong(c.text());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * Texts of the {@code <Items>} members of a CloudFront quantity/items list —
         * {@code node.items("AllowedMethods", "Method")} reads
         * {@code <AllowedMethods><Items><Method>GET</Method></Items></AllowedMethods>}.
         * Returns an empty list when the wrapper or its Items block is absent.
         */
        List<String> items(String wrapperName, String itemName) {
            Node items = path(wrapperName, "Items");
            if (items == null) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Node c : items.children(itemName)) {
                result.add(c.text());
            }
            return result;
        }
    }
}
