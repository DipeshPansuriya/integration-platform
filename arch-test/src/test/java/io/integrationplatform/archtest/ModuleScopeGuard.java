package io.integrationplatform.archtest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Compares the parent pom's modules with arch-test's project dependencies: a production module arch-test does not
 * depend on is invisible to the ArchUnit rules (R08 scope).
 */
final class ModuleScopeGuard {

    private static final String PROJECT_GROUP = "io.integrationplatform";

    /** Modules that hold no production classes (platform-e2e: E15 black-box harness, F17/T142). */
    static final Set<String> TEST_ONLY_MODULES = Set.of("arch-test", "platform-e2e");

    private ModuleScopeGuard() {
    }

    static List<String> modulesOf(Path parentPom) {
        List<String> modules = new ArrayList<>();
        NodeList elements = read(parentPom).getElementsByTagName("module");
        for (int i = 0; i < elements.getLength(); i++) {
            modules.add(elements.item(i).getTextContent().trim());
        }
        return modules;
    }

    static Set<String> projectDependenciesOf(Path archTestPom) {
        Set<String> dependencies = new LinkedHashSet<>();
        NodeList elements = read(archTestPom).getElementsByTagName("dependency");
        for (int i = 0; i < elements.getLength(); i++) {
            Element dependency = (Element) elements.item(i);
            if (PROJECT_GROUP.equals(text(dependency, "groupId"))) {
                dependencies.add(text(dependency, "artifactId"));
            }
        }
        return dependencies;
    }

    static List<String> missingFromScope(List<String> modules, Set<String> dependencies) {
        return modules.stream()
                .filter(module -> !TEST_ONLY_MODULES.contains(module))
                .filter(module -> !dependencies.contains(module))
                .toList();
    }

    private static String text(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        return children.getLength() == 0 ? "" : children.item(0).getTextContent().trim();
    }

    private static Document read(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(pom.toFile());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read " + pom.toAbsolutePath(), failure);
        }
    }
}
