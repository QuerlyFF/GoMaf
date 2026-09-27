package com.mafia.classicmafia;

import com.mafia.classicmafia.service.RulesContentService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RulesContentServiceTests {
    @Test
    void parsesBundledRulesIntoSixSectionsNineteenArticlesAndDefinitions() throws IOException {
        String content = bundledRules();
        var document = new RulesContentService().parse(content);

        assertThat(document.sections()).hasSize(6);
        assertThat(document.sections().get(0).anchor()).isEqualTo("section-1");
        assertThat(document.sections().get(5).anchor()).isEqualTo("section-6");
        assertThat(document.sections().stream().flatMap(section -> section.blocks().stream()).filter(block -> block.type().equals("article"))).hasSize(19);
        assertThat(document.sections().stream().flatMap(section -> section.blocks().stream()).filter(block -> block.type().equals("definition"))).hasSize(54);
        assertThat(document.sections().stream().flatMap(section -> section.blocks().stream()).filter(block -> block.type().equals("clause"))).hasSize(185);
    }

    @Test
    void survivesAdminEditThatRemovesFinalDotFromClauseNumber() throws IOException {
        String content = bundledRules().replace("19.2. Если во время", "19.2 Если во время");
        var document = new RulesContentService().parse(content);

        assertThat(document.sections().stream().flatMap(section -> section.blocks().stream()))
            .anyMatch(block -> block.type().equals("clause") && block.label().equals("19.2."));
    }

    @Test
    void continuationAfterClauseGetsAlignedBlockType() {
        String content = """
            Раздел 1. Термины, определения и сокращения
            Официальные термины
            Термин
            Определение
            Раздел 2 - Общие принципы игры
            9.6. Первый абзац пункта.
            Продолжение того же пункта отдельным абзацем.
            """;
        var document = new RulesContentService().parse(content);

        assertThat(document.sections().get(1).blocks())
            .anyMatch(block -> block.type().equals("continuation") && block.text().startsWith("Продолжение"));
    }

    private String bundledRules() throws IOException {
        try (var stream = getClass().getResourceAsStream("/default-rules.txt")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
