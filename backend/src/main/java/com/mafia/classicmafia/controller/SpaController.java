package com.mafia.classicmafia.controller;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.TournamentRepository;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class SpaController {
    private static final Pattern TITLE = Pattern.compile("(?is)<title>.*?</title>");
    private static final Pattern DESCRIPTION = Pattern.compile("(?is)<meta\\s+name=[\\\"']description[\\\"'][^>]*>");
    private static final List<Role> PUBLIC_PLAYER_ROLES = List.of(Role.PLAYER, Role.MODERATOR);

    private final String indexHtml;
    private final String publicBaseUrl;
    private final UserRepository users;
    private final GameRepository games;
    private final TournamentRepository tournaments;
    private final CurrentUserService currentUserService;

    public SpaController(
        @Value("${app.spa.index-resource:classpath:/static/index.html}") Resource index,
        @Value("${app.public-base-url:https://gomaf.online}") String publicBaseUrl,
        UserRepository users,
        GameRepository games,
        TournamentRepository tournaments,
        CurrentUserService currentUserService
    ) throws IOException {
        if (index.exists()) {
            try (var input = index.getInputStream()) {
                this.indexHtml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            this.indexHtml = "";
        }
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
        this.users = users;
        this.games = games;
        this.tournaments = tournaments;
        this.currentUserService = currentUserService;
    }

    @GetMapping({
        "/", "/news", "/rules", "/rating", "/players", "/players/{id}",
        "/games", "/games/new", "/games/{id}", "/games/{id}/edit",
        "/schedule", "/tournaments", "/tournaments/new", "/tournaments/{id}",
        "/tournaments/{id}/edit", "/profile", "/login", "/register",
        "/admin/moderators"
    })
    ResponseEntity<String> spa(HttpServletRequest request, Authentication authentication) {
        if (indexHtml.isEmpty()) return ResponseEntity.status(503).build();
        String path = request.getRequestURI();
        Metadata metadata = metadataFor(path, authentication);
        if (metadata == null) return NotFoundController.response();

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noCache());
        if (!metadata.indexable()) response.header("X-Robots-Tag", "noindex, nofollow");
        return response.body(applyMetadata(indexHtml, metadata, path));
    }

    private String applyMetadata(String html, Metadata metadata, String path) {
        String title = "<title>" + HtmlUtils.htmlEscape(metadata.title(), StandardCharsets.UTF_8.name()) + "</title>";
        String description = "<meta name=\"description\" content=\"" + HtmlUtils.htmlEscape(metadata.description(), StandardCharsets.UTF_8.name()) + "\" />";
        String result = TITLE.matcher(html).find()
            ? TITLE.matcher(html).replaceFirst(Matcher.quoteReplacement(title))
            : insertHead(html, title);
        result = DESCRIPTION.matcher(result).find()
            ? DESCRIPTION.matcher(result).replaceFirst(Matcher.quoteReplacement(description))
            : insertHead(result, description);
        String tags = metadata.indexable()
            ? "<link rel=\"canonical\" href=\"" + HtmlUtils.htmlEscape(publicBaseUrl + metadata.canonicalPath(), StandardCharsets.UTF_8.name()) + "\" /><meta name=\"robots\" content=\"index,follow\" />"
            : "<meta name=\"robots\" content=\"noindex,nofollow\" />";
        return insertHead(result, tags + profileBannerPreload(path));
    }

    private String profileBannerPreload(String path) {
        if (path == null || !path.matches("/players/[0-9]+")) return "";
        String id = path.substring("/players/".length());
        String base = "/api/v1/players/" + id + "/banner?width=";
        return "<link rel=\"preload\" as=\"image\" imagesrcset=\""
            + base + "640 640w, " + base + "768 768w, " + base + "960 960w, " + base + "1280 1280w\" "
            + "imagesizes=\"(max-width: 640px) calc(100vw - 26px), (max-width: 720px) calc(100vw - 42px), (max-width: 1024px) 960px, 1280px\" "
            + "fetchpriority=\"high\" />";
    }

    private String insertHead(String html, String tags) {
        int index = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        return index >= 0 ? html.substring(0, index) + tags + html.substring(index) : tags + html;
    }

    private Metadata metadataFor(String rawPath, Authentication authentication) {
        String path = rawPath == null || rawPath.isBlank() ? "/" : rawPath;
        return switch (path) {
            case "/", "/news" -> publicMetadata("Новости — GoMaf", "Новости игрового клуба GoMaf: события, объявления и обновления сообщества.", "/news");
            case "/rules" -> publicMetadata("Правила игры в мафию — GoMaf", "Правила классической мафии клуба GoMaf: роли, порядок игры и игровые ситуации.", "/rules");
            case "/players" -> publicMetadata("Игроки — GoMaf", "Игроки GoMaf: профили, рейтинг, статистика и история игр.", "/players");
            case "/games" -> publicMetadata("Игры — GoMaf", "История игр GoMaf: составы, результаты и игровая статистика.", "/games");
            case "/schedule" -> publicMetadata("Расписание — GoMaf", "Актуальное расписание игр и мероприятий клуба GoMaf.", "/schedule");
            case "/tournaments" -> publicMetadata("Турниры — GoMaf", "Турниры GoMaf: даты, участники, игры и результаты.", "/tournaments");
            case "/rating" -> publicMetadata("Рейтинг игроков — GoMaf", "Рейтинг и статистика игроков клуба GoMaf по сыгранным партиям.", "/rating");
            case "/login" -> privateMetadata("Вход — GoMaf");
            case "/register" -> privateMetadata("Регистрация — GoMaf");
            case "/profile" -> privateMetadata("Профиль — GoMaf");
            case "/admin/moderators" -> privateMetadata("Администрирование — GoMaf");
            case "/games/new" -> privateMetadata("Новая игра — GoMaf");
            case "/tournaments/new" -> privateMetadata("Новый турнир — GoMaf");
            default -> dynamicMetadata(path, authentication);
        };
    }

    private Metadata dynamicMetadata(String path, Authentication authentication) {
        if (path.matches("/players/[0-9]+")) {
            Long id = parseId(path, "/players/");
            if (id == null || users.findPublicPlayerProfile(id, PUBLIC_PLAYER_ROLES).isEmpty()) return null;
            return publicMetadata("Профиль игрока — GoMaf", "Профиль игрока GoMaf: статистика и история игр.", path);
        }
        if (path.matches("/games/[0-9]+")) {
            Long id = parseId(path, "/games/");
            if (id == null) return null;
            var game = games.findDetailProjectionById(id).orElse(null);
            if (game == null) return null;
            if (game.hiddenFromPublic()) {
                if (authentication == null || !authentication.isAuthenticated()) return null;
                try {
                    var viewer = currentUserService.requireSecurity(authentication);
                    if (viewer.role() != Role.ADMIN && games.countVisibilityUser(id, viewer.id()) == 0) return null;
                } catch (RuntimeException exception) {
                    return null;
                }
                return privateMetadata("Игра — GoMaf");
            }
            return publicMetadata("Игра — GoMaf", "Результат игры GoMaf: состав, роли и игровая статистика.", path);
        }
        if (path.matches("/tournaments/[0-9]+")) {
            Long id = parseId(path, "/tournaments/");
            if (id == null || !tournaments.existsById(id)) return null;
            return publicMetadata("Турнир — GoMaf", "Турнир GoMaf: даты, участники и связанные игры.", path);
        }
        if (path.matches("/games/[0-9]+/edit") || path.matches("/tournaments/[0-9]+/edit")) {
            return privateMetadata("Редактирование — GoMaf");
        }
        return privateMetadata("GoMaf");
    }

    private Long parseId(String path, String prefix) {
        try {
            long value = Long.parseLong(path.substring(prefix.length()));
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Metadata publicMetadata(String title, String description, String canonicalPath) {
        return new Metadata(title, description, canonicalPath, true);
    }

    private Metadata privateMetadata(String title) {
        return new Metadata(title, "GoMaf — игры, расписание, турниры и рейтинг клуба.", "", false);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?")) {
            throw new IllegalArgumentException("app.public-base-url must be an absolute HTTPS origin");
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record Metadata(String title, String description, String canonicalPath, boolean indexable) { }
}
