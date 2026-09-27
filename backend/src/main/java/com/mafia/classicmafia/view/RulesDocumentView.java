package com.mafia.classicmafia.view;

import java.util.List;

public record RulesDocumentView(
    List<String> introLines,
    List<RulesSectionView> sections
) {
    public boolean isEmpty() {
        return sections.isEmpty();
    }
}
