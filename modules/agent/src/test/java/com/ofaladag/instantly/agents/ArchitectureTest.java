package com.ofaladag.instantly.agents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.Test;

class ArchitectureTest {
    @Test
    void hexagonalDependenciesStayDirected() {
        var classes = new ClassFileImporter().importPackages("com.ofaladag.instantly.agents");
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..application..",
                        "..adapter..",
                        "..bootstrap..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.openai..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..adapter..",
                        "..bootstrap..",
                        "org.springframework..",
                        "tools.jackson..",
                        "com.openai..")
                .check(classes);
    }
}
