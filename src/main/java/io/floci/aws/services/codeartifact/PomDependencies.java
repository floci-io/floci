package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.XmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pulls direct dependencies out of a Maven POM's XML for {@code ListPackageVersionDependencies},
 * using the shared, hardened {@link XmlParser#parseDocument}. Only ever walks direct children of
 * a known element, never {@link Document#getElementsByTagName}: that method recurses into every
 * matching descendant, which would wrongly pull managed version pins out of
 * {@code <project>/<dependencyManagement>/<dependencies>} (not real dependencies) and
 * conditionally-activated dependencies out of {@code <project>/<profiles>/<profile>/<dependencies>}
 * (not always-active) as if they were direct dependencies of the version itself.
 */
final class PomDependencies {

    private PomDependencies() {
    }

    /**
     * {@code pomBytes} is the exact asset this package version's own Maven coordinate names
     * ({@code <artifactId>-<version>.pom}), fetched the same way any other asset is. No
     * {@code ${property}} placeholder resolution is performed on a dependency's raw
     * {@code <version>} text: a documented limitation, not a build gap, since resolving effective
     * POM properties would need a full Maven model builder this emulator does not have.
     */
    static List<CodeArtifactService.PackageDependency> parse(byte[] pomBytes) {
        Document document;
        try {
            document = XmlParser.parseDocument(pomBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse the package version's POM file.", e);
        }
        Element project = document.getDocumentElement();
        List<CodeArtifactService.PackageDependency> dependencies = new ArrayList<>();
        directChild(project, "parent").ifPresent(parent -> {
            String artifactId = directChildText(parent, "artifactId").orElse(null);
            if (artifactId != null) {
                dependencies.add(new CodeArtifactService.PackageDependency(
                        directChildText(parent, "groupId").orElse(null), artifactId, "parent",
                        directChildText(parent, "version").orElse(null)));
            }
        });
        directChild(project, "dependencies").ifPresent(dependenciesElement -> {
            for (Element dependency : directChildren(dependenciesElement, "dependency")) {
                String artifactId = directChildText(dependency, "artifactId").orElse(null);
                if (artifactId == null) {
                    continue;
                }
                String groupId = directChildText(dependency, "groupId").orElse(null);
                String version = directChildText(dependency, "version").orElse(null);
                String scope = directChildText(dependency, "scope").orElse(null);
                boolean optional = "true".equalsIgnoreCase(directChildText(dependency, "optional").orElse("false"));
                String dependencyType = optional ? "optional" : scope != null ? scope : "compile";
                dependencies.add(new CodeArtifactService.PackageDependency(groupId, artifactId, dependencyType,
                        version));
            }
        });
        return dependencies;
    }

    private static Optional<Element> directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(((Element) child).getLocalName())) {
                return Optional.of((Element) child);
            }
        }
        return Optional.empty();
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(((Element) child).getLocalName())) {
                matches.add((Element) child);
            }
        }
        return matches;
    }

    private static Optional<String> directChildText(Element parent, String localName) {
        return directChild(parent, localName).map(Element::getTextContent).map(String::trim)
                .filter(text -> !text.isEmpty());
    }
}
