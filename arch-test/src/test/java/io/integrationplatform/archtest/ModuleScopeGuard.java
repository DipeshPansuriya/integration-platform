package io.integrationplatform.archtest;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

    /**
     * Row 327 T157: the test-only tenant B seed's package and marker (platform-e2e TenantSeedPlanTest pins both) - no
     * production file may name either.
     */
    static final Set<String> SEED_MARKERS = Set.of("io.integrationplatform.e2e.seed", "e2e-seed");

    /** Production files outside the Maven modules' src/main: the console source, the whole compose tree and its
     *  provisioning scripts, and the build-check scripts. */
    private static final List<String> OTHER_PRODUCTION_ROOTS =
            List.of("ui", "compose", "build-checks");

    /** The root whose installed packages are never walked (B31): the console only. Under a module's src/main,
     *  compose or build-checks a folder named node_modules is production source and IS read (T189). */
    private static final String GENERATED_OUTPUT_ROOT = "ui";

    /** Folder names never walked under {@link #GENERATED_OUTPUT_ROOT}: the console's installed packages (B31). The built bundle ui/dist is NOT skipped (B31-1): it is what ships. */
    private static final Set<String> GENERATED_FOLDERS = Set.of("node_modules");

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

    /** The test-only modules under {@code root} that hold a {@code src/main} (none may: they are skipped above). */
    static List<String> testOnlyModulesWithProductionSources(Path root) {
        return TEST_ONLY_MODULES.stream().sorted()
                .filter(module -> Files.exists(root.resolve(module).resolve("src/main")))
                .toList();
    }

    /** The modules (other than {@code artifactId} itself) whose pom names {@code artifactId} as a dependency. */
    static List<String> modulesDependingOn(Path root, List<String> modules, String artifactId) {
        return modules.stream()
                .filter(module -> !module.equals(artifactId))
                .filter(module -> Files.exists(root.resolve(module).resolve("pom.xml")))
                .filter(module -> projectDependenciesOf(root.resolve(module).resolve("pom.xml")).contains(artifactId))
                .toList();
    }

    /**
     * Every file (root-relative, {@code /}-separated, sorted) under a module's {@code src/main} - resources and
     * db/migration included - or under {@link #OTHER_PRODUCTION_ROOTS} whose content names one of {@code markers}.
     */
    static List<String> productionFilesNaming(Path root, List<String> modules, Set<String> markers) {
        List<Path> roots = new ArrayList<>();
        modules.forEach(module -> roots.add(root.resolve(module).resolve("src/main")));
        OTHER_PRODUCTION_ROOTS.forEach(other -> roots.add(root.resolve(other)));
        List<String> naming = new ArrayList<>();
        for (Path production : roots) {
            if (!Files.exists(production)) {
                continue;
            }
            try {
                Files.walkFileTree(production, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        // B31: installed/generated trees are not our source and took minutes to read
                        return production.equals(root.resolve(GENERATED_OUTPUT_ROOT))
                                && GENERATED_FOLDERS.contains(String.valueOf(dir.getFileName()))
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && names(file, markers)) {
                            naming.add(root.relativize(file).toString().replace(File.separatorChar, '/'));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot walk " + production.toAbsolutePath(), failure);
            }
        }
        return naming.stream().sorted().toList();
    }

    private static boolean names(Path file, Set<String> markers) {
        try {
            // ISO-8859-1 reads any bytes (images included) without a decoding failure; the markers are ASCII.
            String content = Files.readString(file, StandardCharsets.ISO_8859_1);
            return markers.stream().anyMatch(content::contains);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + file.toAbsolutePath(), failure);
        }
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
