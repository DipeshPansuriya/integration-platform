package io.integrationplatform.config.rule.parity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.adapter.in.RuleEvaluationController;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.parity.planted.controller.PlantedEngineController;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * T-029 (E6-F3-S1) negative (a): the test panel's evaluate endpoint can reach an evaluation ONLY through
 * {@link RuleEvaluationPort}. Checked on the COMPILED classes (java.lang.classfile, every type the bytecode names -
 * imports, fully-qualified uses, signatures, annotations), so a source-text trick cannot slip past it:
 * <ol>
 *   <li>no class in the package references an engine type: anything in {@code io.gorules}, the application's
 *       {@code DecisionEngine} or the {@code rule/adapter/out} engine adapter;</li>
 *   <li>the class declaring the {@code @PostMapping(".../evaluate")} endpoint holds and is constructed with
 *       {@link RuleEvaluationPort} only, and calls {@code RuleEvaluationPort.evaluate}.</li>
 * </ol>
 * The production package {@code config.rule.adapter.in} passes; the planted controller package
 * ({@link PlantedEngineController}, which imports ZEN directly) must fail it (R02).
 */
class EvaluateThroughPortArchitectureTest {

    private static final String PORT = RuleEvaluationPort.class.getName().replace('.', '/');

    private static final List<String> ENGINE_TYPES = List.of(
            "io/gorules/",
            "io/integrationplatform/config/rule/application/DecisionEngine",
            "io/integrationplatform/config/rule/adapter/out/");

    @Test
    @DisplayName("Production: the evaluate endpoint in config.rule.adapter.in reaches evaluation only through the port")
    void productionEndpointGoesThroughThePortOnly() {
        List<String> violations = violations(RuleEvaluationController.class);

        assertThat(violations).as("guard findings for config.rule.adapter.in").isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("R02 planted violation: a controller that imports the engine directly fails the guard")
    void aPlantedControllerImportingTheEngineFailsTheGuard() {
        List<String> violations = violations(PlantedEngineController.class);
        System.out.println("PLANTED-ENGINE-CONTROLLER guard findings:\n  " + String.join("\n  ", violations));

        assertThat(violations).as("the planted controller is reported")
                .anyMatch(finding -> finding.contains("PlantedEngineController references engine type io/gorules/"))
                .anyMatch(finding -> finding.contains("PlantedEngineController") && finding.contains("ZenEngine"))
                .anyMatch(finding -> finding.contains("never calls RuleEvaluationPort.evaluate"));
    }

    // ------------------------------------------------------------------ the guard

    /**
     * Every finding for the package of {@code anchor}, read from the classpath root that holds {@code anchor} itself
     * (main classes and test classes share package names - lane B's guard test sits in test {@code adapter/in}); empty
     * means the package keeps the rule.
     */
    static List<String> violations(Class<?> anchor) {
        String packageName = anchor.getPackageName();
        List<String> findings = new ArrayList<>();
        boolean endpointFound = false;
        for (Path classFile : classFiles(anchor)) {
            ClassModel model = parse(classFile);
            String name = model.thisClass().asInternalName();
            findings.addAll(engineReferences(name, model));
            Class<?> type = load(name);
            if (declaresEvaluateEndpoint(type)) {
                endpointFound = true;
                findings.addAll(dependsOnThePortOnly(type));
                if (!callsPortEvaluate(model)) {
                    findings.add(name + " declares the evaluate endpoint but never calls RuleEvaluationPort.evaluate");
                }
            }
        }
        if (!endpointFound) {
            findings.add(packageName + " declares no evaluate endpoint - the guard found nothing to check");
        }
        return findings;
    }

    private static List<String> engineReferences(String name, ClassModel model) {
        List<String> findings = new ArrayList<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof Utf8Entry utf8) {
                String text = utf8.stringValue();
                for (String engine : ENGINE_TYPES) {
                    if (text.contains(engine)) {
                        findings.add(name + " references engine type " + engine + " (" + text + ")");
                    }
                }
            }
        }
        return findings;
    }

    private static boolean declaresEvaluateEndpoint(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping != null && Stream.concat(Arrays.stream(mapping.value()), Arrays.stream(mapping.path()))
                    .anyMatch(path -> path.endsWith("/evaluate"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> dependsOnThePortOnly(Class<?> type) {
        List<String> findings = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() != RuleEvaluationPort.class) {
                findings.add(type.getName() + " holds " + field.getType().getName() + " " + field.getName()
                        + " (only RuleEvaluationPort is allowed)");
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 1 || parameters[0] != RuleEvaluationPort.class) {
                findings.add(type.getName() + " is constructed with " + Arrays.toString(parameters)
                        + " (only RuleEvaluationPort is allowed)");
            }
        }
        return findings;
    }

    private static boolean callsPortEvaluate(ClassModel model) {
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof InterfaceMethodRefEntry call && call.owner().asInternalName().equals(PORT)
                    && call.name().stringValue().equals("evaluate")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ class files from the test classpath

    private static List<Path> classFiles(Class<?> anchor) {
        String packageName = anchor.getPackageName();
        URL own = anchor.getResource(anchor.getSimpleName() + ".class");
        assertThat(own).as("the compiled class %s is on the classpath", anchor.getName()).isNotNull();
        try (Stream<Path> files = Files.list(Path.of(own.toURI()).getParent())) {
            List<Path> classes = files.filter(path -> path.toString().endsWith(".class")).sorted().toList();
            assertThat(classes).as("class files of %s", packageName).isNotEmpty();
            return classes;
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot list " + packageName, e);
        }
    }

    private static ClassModel parse(Path classFile) {
        try {
            return ClassFile.of().parse(Files.readAllBytes(classFile));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + classFile, e);
        }
    }

    private static Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false,
                    EvaluateThroughPortArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("cannot load " + internalName, e);
        }
    }
}
