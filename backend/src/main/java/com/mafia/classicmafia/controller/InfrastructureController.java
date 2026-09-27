package com.mafia.classicmafia.controller;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.WebRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Controller
public class InfrastructureController {
    private static final CacheControl PUBLIC_REVALIDATED_MEDIA = CacheControl.noCache().cachePublic().noTransform();

    private final UserRepository users;
    private final DataSource dataSource;

    public InfrastructureController(UserRepository users, DataSource dataSource) {
        this.users = users;
        this.dataSource = dataSource;
    }

    @GetMapping("/avatar/{id}")
    @ResponseBody
    ResponseEntity<?> avatar(
        @PathVariable Long id,
        WebRequest webRequest,
        HttpServletResponse response
    ) {
        var media = users.findPublicAvatarMedia(id, List.of(Role.PLAYER, Role.MODERATOR)).orElse(null);
        boolean safe = media != null
            && media.data() != null
            && media.data().length > 0
            && Integer.valueOf(1).equals(media.safetyVersion())
            && "image/png".equals(media.contentType());

        if (!safe) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/css/default-avatar.svg"))
                .cacheControl(CacheControl.noStore())
                .build();
        }

        byte[] body = media.data();
        String etag = strongEtag("legacy-avatar:1", body);
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_REVALIDATED_MEDIA.getHeaderValue());
        if (webRequest.checkNotModified(etag)) {
            return null;
        }

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline; filename=avatar.png")
            .cacheControl(PUBLIC_REVALIDATED_MEDIA)
            .eTag(etag)
            .body(body);
    }

    @RequestMapping("/forbidden")
    @ResponseBody
    ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("status", "forbidden"));
    }

    @GetMapping("/healthz")
    @ResponseBody
    ResponseEntity<Map<String, String>> health() {
        try (var connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
        } catch (SQLException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "database-unavailable"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("status", "database-unavailable"));
    }

    private String strongEtag(String representation, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(representation.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(data);
            return "\"" + HexFormat.of().formatHex(digest.digest()) + "\"";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
