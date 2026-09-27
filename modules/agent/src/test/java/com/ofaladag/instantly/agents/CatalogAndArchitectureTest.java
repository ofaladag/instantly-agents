package com.ofaladag.instantly.agents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.*;

import com.ofaladag.instantly.agents.adapter.out.characters.MarkdownCharacterCatalog;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.Test;

class CatalogAndArchitectureTest {
    @Test
    void rosterMatchesApprovedComposition() {
        var catalog = new MarkdownCharacterCatalog("classpath*:characters/*.md");
        assertThat(catalog.all()).hasSize(50);
        assertThat(catalog.all().stream().filter(p -> p.gender().equals("female"))).hasSize(30);
        assertThat(catalog.all().stream().filter(p -> p.gender().equals("male"))).hasSize(20);
        assertThat(catalog.all())
                .allSatisfy(
                        p -> {
                            assertThat(p.age())
                                    .isBetween(20, p.gender().equals("female") ? 35 : 30);
                            assertThat(p.persona())
                                    .contains("## Voice", "## Background", "## Social approach");
                        });
        assertThat(catalog.all().stream().map(p -> p.persona())).doesNotHaveDuplicates();
        assertThat(catalog.commonInstructions())
                .contains("without sexual content", "language", "untrusted");
        assertThatThrownBy(() -> catalog.get("missing-character"))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
