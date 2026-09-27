package com.mafia.classicmafia.service;

import com.mafia.classicmafia.view.RulesBlockView;
import com.mafia.classicmafia.view.RulesDocumentView;
import com.mafia.classicmafia.view.RulesSectionView;
import com.mafia.classicmafia.view.RulesSubnavView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RulesContentService {
    private static final Pattern SECTION = Pattern.compile(
        "^Раздел\\s+(\\d+)\\s*(?:[.\\-–—])?\\s*(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern CLAUSE = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\.?\\s+(.*)$");
    private static final Pattern ARTICLE = Pattern.compile(
        "^Статья\\s+(\\d+)\\.?\\s*(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Set<String> KNOWN_SUBHEADINGS = Set.of(
        "официальные термины",
        "игровые термины",
        "рекомендации для ведущего игры"
    );

    private static final Map<String, Integer> ARTICLE_HEADINGS = Map.ofEntries(
        Map.entry("первая ночная фаза игры", 7),
        Map.entry("дневная фаза игры", 8),
        Map.entry("голосование", 9),
        Map.entry("вторая и последующие ночные фазы игры", 10),
        Map.entry("дальнейший ход игры", 11),
        Map.entry("фол", 13),
        Map.entry("предупреждение", 14),
        Map.entry("серая дисциплинарная карточка", 15),
        Map.entry("дисквалифицирующие фолы. удаление из игры", 16),
        Map.entry("победа противоположной команды", 17)
    );

    private static final Map<Integer, String> ARTICLE_TITLES = Map.ofEntries(
        Map.entry(1, "Термины, определения и сокращения"),
        Map.entry(2, "Общие принципы и условия участия"),
        Map.entry(3, "Порядок проведения Игры и использование игровой локации"),
        Map.entry(4, "Официальный язык Игры"),
        Map.entry(5, "Требования к поведению и готовности Игроков"),
        Map.entry(6, "Подготовка и начало Игры"),
        Map.entry(7, "Первая Ночная фаза Игры"),
        Map.entry(8, "Дневная фаза Игры"),
        Map.entry(9, "Голосование"),
        Map.entry(10, "Вторая и последующие Ночные фазы Игры"),
        Map.entry(11, "Дальнейший ход Игры"),
        Map.entry(12, "Нарушения Правил Игры"),
        Map.entry(13, "Фол"),
        Map.entry(14, "Предупреждение"),
        Map.entry(15, "Серая дисциплинарная карточка"),
        Map.entry(16, "Дисквалифицирующие фолы. Удаление из Игры"),
        Map.entry(17, "Победа противоположной команды"),
        Map.entry(18, "Особые ситуации"),
        Map.entry(19, "Судейство и система оценок действий Игроков")
    );

    public RulesDocumentView parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new RulesDocumentView(List.of(), List.of());
        }

        String normalized = rawContent
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n');

        List<String> lines = normalized.lines().toList();
        int bodyStart = findBodyStart(lines);
        List<String> intro = extractIntro(lines, bodyStart);
        List<MutableSection> sections = parseSections(lines, bodyStart);

        return new RulesDocumentView(
            List.copyOf(intro),
            sections.stream().map(MutableSection::freeze).toList()
        );
    }

    private int findBodyStart(List<String> lines) {
        int firstSectionOne = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = SECTION.matcher(lines.get(i).trim());
            if (matcher.matches() && "1".equals(matcher.group(1))) {
                if (firstSectionOne < 0) {
                    firstSectionOne = i;
                } else {
                    return i;
                }
            }
        }
        return Math.max(firstSectionOne, 0);
    }

    private List<String> extractIntro(List<String> lines, int bodyStart) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < bodyStart; i++) {
            String text = lines.get(i).trim();
            if (text.isBlank() || text.matches("_+")) {
                continue;
            }
            if (text.equalsIgnoreCase("ОГЛАВЛЕНИЕ И НАВИГАЦИЯ ПО РАЗДЕЛАМ")) {
                break;
            }
            result.add(text);
        }
        return result;
    }

    private List<MutableSection> parseSections(List<String> lines, int bodyStart) {
        List<MutableSection> sections = new ArrayList<>();
        MutableSection current = null;
        int subheadingCounter = 0;
        int currentArticle = -1;
        boolean definitionMode = false;
        String pendingTerm = null;
        String previousBlockType = "";

        for (int i = bodyStart; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isBlank() || trimmed.matches("_+")) {
                continue;
            }

            Matcher sectionMatcher = SECTION.matcher(trimmed);
            if (sectionMatcher.matches()) {
                current = new MutableSection(
                    "section-" + sectionMatcher.group(1),
                    trimmed,
                    Integer.parseInt(sectionMatcher.group(1))
                );
                sections.add(current);
                subheadingCounter = 0;
                currentArticle = -1;
                definitionMode = false;
                pendingTerm = null;
                previousBlockType = "";

                int initialArticle = defaultArticleForSection(current.number);
                if (initialArticle > 0) {
                    addArticle(current, initialArticle, ARTICLE_TITLES.get(initialArticle));
                    currentArticle = initialArticle;
                    previousBlockType = "article";
                }
                continue;
            }

            if (current == null) {
                continue;
            }

            Matcher explicitArticle = ARTICLE.matcher(trimmed);
            if (explicitArticle.matches()) {
                int number = Integer.parseInt(explicitArticle.group(1));
                String title = explicitArticle.group(2).isBlank()
                    ? ARTICLE_TITLES.getOrDefault(number, "")
                    : explicitArticle.group(2).trim();
                addArticle(current, number, title);
                currentArticle = number;
                definitionMode = false;
                pendingTerm = null;
                previousBlockType = "article";
                continue;
            }

            String lowered = trimmed.toLowerCase(Locale.ROOT);
            Integer headingArticle = current.number == 1 ? null : ARTICLE_HEADINGS.get(lowered);
            if (headingArticle != null) {
                if (currentArticle != headingArticle) {
                    addArticle(current, headingArticle, trimmed);
                    currentArticle = headingArticle;
                }
                definitionMode = false;
                pendingTerm = null;
                previousBlockType = "article";
                continue;
            }

            if (KNOWN_SUBHEADINGS.contains(lowered)) {
                subheadingCounter++;
                String anchor = current.anchor + "-part-" + subheadingCounter;
                current.navigation.add(new RulesSubnavView(anchor, trimmed));
                current.blocks.add(new RulesBlockView("subheading", anchor, trimmed, ""));
                definitionMode = current.number == 1;
                pendingTerm = null;
                previousBlockType = "subheading";
                continue;
            }

            if (current.number == 1 && isDefinitionHeader(trimmed)) {
                continue;
            }

            if (trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                current.blocks.add(new RulesBlockView("bullet", "", "", trimmed.substring(2).trim()));
                previousBlockType = "bullet";
                continue;
            }

            Matcher clause = CLAUSE.matcher(trimmed);
            if (clause.matches()) {
                int major = Integer.parseInt(clause.group(1).split("\\.", 2)[0]);
                if (currentArticle != major) {
                    addArticle(current, major, ARTICLE_TITLES.getOrDefault(major, ""));
                    currentArticle = major;
                }
                current.blocks.add(new RulesBlockView("clause", "", clause.group(1) + ".", clause.group(2)));
                definitionMode = false;
                pendingTerm = null;
                previousBlockType = "clause";
                continue;
            }

            if (current.number == 1 && definitionMode) {
                if (pendingTerm == null) {
                    pendingTerm = trimmed;
                } else {
                    current.blocks.add(new RulesBlockView("definition", "", pendingTerm, trimmed));
                    pendingTerm = null;
                    previousBlockType = "definition";
                }
                continue;
            }

            String type = ("clause".equals(previousBlockType) || "continuation".equals(previousBlockType))
                ? "continuation"
                : "paragraph";
            current.blocks.add(new RulesBlockView(type, "", "", trimmed));
            previousBlockType = type;
        }

        return sections;
    }

    private void addArticle(MutableSection section, int number, String title) {
        String label = "Статья " + number + "." + (title == null || title.isBlank() ? "" : " " + title);
        String anchor = section.anchor + "-article-" + number;
        if (section.navigation.stream().noneMatch(item -> item.anchor().equals(anchor))) {
            section.navigation.add(new RulesSubnavView(anchor, label));
        }
        if (section.blocks.stream().noneMatch(block -> block.type().equals("article") && block.anchor().equals(anchor))) {
            section.blocks.add(new RulesBlockView("article", anchor, label, ""));
        }
    }

    private int defaultArticleForSection(int sectionNumber) {
        return switch (sectionNumber) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 6;
            case 4 -> 12;
            case 5 -> 18;
            case 6 -> 19;
            default -> -1;
        };
    }

    private boolean isDefinitionHeader(String text) {
        return text.equalsIgnoreCase("Термин") || text.equalsIgnoreCase("Определение");
    }

    private static final class MutableSection {
        private final String anchor;
        private final String title;
        private final int number;
        private final List<RulesSubnavView> navigation = new ArrayList<>();
        private final List<RulesBlockView> blocks = new ArrayList<>();

        private MutableSection(String anchor, String title, int number) {
            this.anchor = anchor;
            this.title = title;
            this.number = number;
        }

        private RulesSectionView freeze() {
            return new RulesSectionView(
                anchor,
                title,
                List.copyOf(navigation),
                List.copyOf(blocks)
            );
        }
    }
}
