package com.traderbro;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * Enforces the architecture invariants that package discipline (not the compiler) protects:
 * the domain/core is SDK-free and Spring-web-free, data never depends on execution, and core
 * never depends on execution.
 */
class ArchitectureTest {

    private static final String CORE = "com.traderbro.core..";
    private static final String DATA = "com.traderbro.data..";
    private static final String EXECUTION = "com.traderbro.execution..";

    @Test
    void coreDoesNotDependOnBrokerSdk() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage("ru.tinkoff..")
                .check(new ClassFileImporter().importPackages("com.traderbro"));
    }

    @Test
    void coreDoesNotDependOnSpringWeb() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..",
                        "jakarta.servlet..")
                .check(new ClassFileImporter().importPackages("com.traderbro"));
    }

    @Test
    void dataDoesNotDependOnExecution() {
        noClasses().that().resideInAPackage(DATA)
                .should().dependOnClassesThat().resideInAPackage(EXECUTION)
                .check(new ClassFileImporter().importPackages("com.traderbro"));
    }

    @Test
    void coreDoesNotDependOnExecution() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAPackage(EXECUTION)
                .check(new ClassFileImporter().importPackages("com.traderbro"));
    }
}