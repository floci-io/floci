package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.XmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class SAMLXml {
    private SAMLXml() {
    }

    static Document document(String xml) throws Exception {
        return XmlParser.parseDocument(xml);
    }

    static String text(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    static String text(Node parent, String namespace, String localName) {
        NodeList nodes = ((org.w3c.dom.Element) parent).getElementsByTagNameNS(namespace, localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
}
