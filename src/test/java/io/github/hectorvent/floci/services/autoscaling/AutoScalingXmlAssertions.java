package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.XmlParser;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AutoScalingXmlAssertions {

    static final String XML_NAMESPACE = "http://autoscaling.amazonaws.com/doc/2011-01-01/";

    private AutoScalingXmlAssertions() {
    }

    static Element assertModeledNamespace(String xml) throws Exception {
        Element root = XmlParser.parseDocument(xml).getDocumentElement();
        assertEquals(XML_NAMESPACE, root.getNamespaceURI());
        NodeList descendants = root.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            assertEquals(XML_NAMESPACE, descendants.item(i).getNamespaceURI(),
                    descendants.item(i).getNodeName());
        }
        return root;
    }
}
