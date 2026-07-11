package io.github.hectorvent.floci.services.s3;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

record S3AclPolicy(List<S3AclPolicy.Grant> grants) {

    private static final Logger LOG = Logger.getLogger(S3AclPolicy.class);
    static final String ALL_USERS_GROUP_URI = "http://acs.amazonaws.com/groups/global/AllUsers";

    static S3AclPolicy parse(String acl) throws AclParseException {
        try {
            return fromDocument(parseXml(acl));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AclParseException(e);
        }
    }

    private static Document parseXml(String acl) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new SilentThrowingErrorHandler());
        return builder.parse(new InputSource(new StringReader(acl)));
    }

    private static S3AclPolicy fromDocument(Document document) {
        Element accessControlPolicy = document.getDocumentElement();
        if (accessControlPolicy == null || !"AccessControlPolicy".equals(accessControlPolicy.getLocalName())) {
            return new S3AclPolicy(List.of());
        }
        Element accessControlList = firstChildElement(accessControlPolicy, "AccessControlList");
        if (accessControlList == null) {
            return new S3AclPolicy(List.of());
        }

        NodeList grantNodes = accessControlList.getChildNodes();
        List<Grant> grants = new ArrayList<>();
        for (int index = 0; index < grantNodes.getLength(); index++) {
            if (grantNodes.item(index) instanceof Element grantElement && "Grant".equals(grantElement.getLocalName())) {
                grants.add(parseGrant(grantElement));
            }
        }
        return new S3AclPolicy(List.copyOf(grants));
    }

    static final class AclParseException extends Exception {
        AclParseException(Throwable cause) {
            super("Failed to parse S3 ACL", cause);
        }
    }

    private static Grant parseGrant(Element grantElement) {
        Element granteeElement = firstChildElement(grantElement, "Grantee");
        String uri = granteeElement != null ? descendantText(granteeElement, "URI") : null;
        String permissionText = childText(grantElement, "Permission");
        return new Grant(new Grantee(uri), Permission.fromXml(permissionText));
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        Element child = firstChildElement(parent, localName);
        return child != null ? normalizedText(child) : null;
    }

    private static String descendantText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return normalizedText(nodes.item(0));
    }

    private static String normalizedText(Node node) {
        String text = node.getTextContent();
        return text != null ? text.trim() : null;
    }

    record Grant(Grantee grantee, Permission permission) {
        boolean allowsPublicRead() {
            return grantee.isAllUsersGroup() && permission.allowsRead();
        }
    }

    record Grantee(String uri) {
        boolean isAllUsersGroup() {
            return ALL_USERS_GROUP_URI.equals(uri);
        }
    }

    enum Permission {
        READ,
        WRITE,
        READ_ACP,
        WRITE_ACP,
        FULL_CONTROL,
        UNKNOWN;

        static Permission fromXml(String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            try {
                return Permission.valueOf(value.trim());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }

        boolean allowsRead() {
            return this == READ || this == FULL_CONTROL;
        }
    }

    private static final class SilentThrowingErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) {
        }

        @Override
        public void error(SAXParseException exception) {
            LOG.debugv(exception, "Recoverable S3 ACL XML parse error");
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
