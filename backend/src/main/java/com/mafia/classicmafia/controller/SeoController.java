package com.mafia.classicmafia.controller;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.GameRepository;
import com.mafia.classicmafia.repository.TournamentRepository;
import com.mafia.classicmafia.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;
import java.util.List;

@Controller
public class SeoController {
    private static final List<Role> PUBLIC_PLAYER_ROLES = List.of(Role.PLAYER, Role.MODERATOR);
    private static final CacheControl DISCOVERY_CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final String publicBaseUrl;
    private final UserRepository users;
    private final GameRepository games;
    private final TournamentRepository tournaments;

    public SeoController(
        @Value("${app.public-base-url:https://gomaf.online}") String publicBaseUrl,
        UserRepository users,
        GameRepository games,
        TournamentRepository tournaments
    ) {
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
        this.users = users;
        this.games = games;
        this.tournaments = tournaments;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> robots() {
        String body = """
            User-agent: *
            Allow: /
            Disallow: /admin/
            Disallow: /profile
            Sitemap: %s/sitemap.xml
            """.formatted(publicBaseUrl);
        return ResponseEntity.ok().cacheControl(DISCOVERY_CACHE).body(body);
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String path : List.of("/news", "/rules", "/players", "/games", "/schedule", "/tournaments", "/rating")) {
            appendUrl(xml, path);
        }
        for (Long id : users.findPublicPlayerIds(PUBLIC_PLAYER_ROLES)) appendUrl(xml, "/players/" + id);
        for (Long id : games.findPublicGameIds()) appendUrl(xml, "/games/" + id);
        for (Long id : tournaments.findPublicTournamentIds()) appendUrl(xml, "/tournaments/" + id);
        xml.append("</urlset>\n");
        return ResponseEntity.ok().cacheControl(DISCOVERY_CACHE).body(xml.toString());
    }

    private void appendUrl(StringBuilder xml, String path) {
        xml.append("  <url><loc>").append(publicBaseUrl).append(path).append("</loc></url>\n");
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?")) {
            throw new IllegalArgumentException("app.public-base-url must be an absolute HTTPS origin");
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
