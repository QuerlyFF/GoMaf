package com.mafia.classicmafia.api.dto.content;

import com.mafia.classicmafia.view.RulesDocumentView;

public record RulesResponse(String title, RulesDocumentView document, String editableContent, boolean manageable) {
}
