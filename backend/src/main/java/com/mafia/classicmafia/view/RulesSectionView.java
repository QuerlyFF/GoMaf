package com.mafia.classicmafia.view;

import java.util.List;

public record RulesSectionView(
    String anchor,
    String title,
    List<RulesSubnavView> navigation,
    List<RulesBlockView> blocks
) {
}
