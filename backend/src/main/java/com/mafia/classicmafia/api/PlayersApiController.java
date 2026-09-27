package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.ApiPageResponse;
import com.mafia.classicmafia.api.dto.PlayerGameResponse;
import com.mafia.classicmafia.api.dto.PlayerListItemResponse;
import com.mafia.classicmafia.api.dto.PlayerProfileResponse;
import com.mafia.classicmafia.service.AvatarService;
import com.mafia.classicmafia.service.PlayerReadService;
import com.mafia.classicmafia.service.ProfileBannerService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v1/players")
public class PlayersApiController {
    private static final CacheControl PUBLIC_REVALIDATED_MEDIA = CacheControl.noCache().cachePublic().noTransform();

    private final PlayerReadService players;
    private final AvatarService avatarService;
    private final ProfileBannerService profileBannerService;

    public PlayersApiController(
        PlayerReadService players,
        AvatarService avatarService,
        ProfileBannerService profileBannerService
    ) {
        this.players = players;
        this.avatarService = avatarService;
        this.profileBannerService = profileBannerService;
    }

    @GetMapping
    ApiPageResponse<PlayerListItemResponse> list(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Integer size,
        @RequestParam(defaultValue = "rating,desc") String sort
    ) {
        return players.listPlayers(q, page, size, sort);
    }

    @GetMapping("/{id}")
    ApiDataResponse<PlayerProfileResponse> profile(@PathVariable Long id) {
        return new ApiDataResponse<>(players.getProfile(id));
    }

    @GetMapping("/{id}/games")
    ApiPageResponse<PlayerGameResponse> games(
        @PathVariable Long id,
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Integer size
    ) {
        return players.getGames(id, authentication, page, size);
    }

    @GetMapping("/{id}/avatar")
    ResponseEntity<?> avatar(
        @PathVariable Long id,
        @RequestParam(defaultValue = "512") int size,
        WebRequest webRequest,
        HttpServletResponse response
    ) {
        if (size != AvatarService.MAX_STORED_SIDE
            && size != AvatarService.PUBLIC_PROFILE_SIDE
            && size != AvatarService.PUBLIC_LIST_SIDE) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "AVATAR_SIZE_INVALID",
                "Доступны варианты аватара 128, 256 и 512 пикселей."
            );
        }

        var media = players.requirePublicAvatarMedia(id);
        boolean safe = media.data() != null
            && media.data().length > 0
            && Integer.valueOf(AvatarService.SAFETY_VERSION).equals(media.safetyVersion())
            && "image/png".equals(media.contentType());

        if (!safe) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/css/default-avatar.svg"))
                .cacheControl(CacheControl.noStore())
                .build();
        }

        byte[] source = media.data();
        String etag = strongEtag(
            "avatar:" + AvatarService.PUBLIC_VARIANT_VERSION + ":" + size,
            source
        );
        if (notModified(webRequest, response, etag)) {
            return null;
        }

        byte[] body;
        String responseMediaType;
        try {
            var rendered = avatarService.renderPublicVariantPayload(source, size);
            body = rendered.data();
            responseMediaType = rendered.mediaType();
        } catch (IOException | IllegalArgumentException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "AVATAR_PROCESSING_FAILED",
                "Не удалось подготовить аватар."
            );
        }

        String filename = "image/jpeg".equals(responseMediaType) ? "avatar.jpg" : "avatar.png";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(responseMediaType))
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline; filename=" + filename)
            .cacheControl(PUBLIC_REVALIDATED_MEDIA)
            .eTag(etag)
            .body(body);
    }

    @GetMapping("/{id}/banner")
    ResponseEntity<?> banner(
        @PathVariable Long id,
        @RequestParam(required = false) Integer width,
        WebRequest webRequest,
        HttpServletResponse response
    ) {
        if (width != null && !profileBannerService.isSupportedPublicWidth(width)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "BANNER_WIDTH_INVALID",
                "Доступны варианты баннера шириной 640, 768, 960, 1280 и 1920 пикселей."
            );
        }
        var media = players.requirePublicBannerMedia(id);
        boolean safe = media.data() != null
            && media.data().length > 0
            && Integer.valueOf(ProfileBannerService.SAFETY_VERSION).equals(media.safetyVersion())
            && ("image/png".equals(media.contentType())
                || "image/jpeg".equals(media.contentType())
                || "image/gif".equals(media.contentType()));

        if (!safe) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/css/default-profile-banner.svg"))
                .cacheControl(CacheControl.noStore())
                .build();
        }

        byte[] source = media.data();
        String representation = "banner:" + ProfileBannerService.PUBLIC_VARIANT_VERSION + ":"
            + media.contentType() + ":" + (width == null ? "original" : width);
        String etag = strongEtag(representation, source);
        if (notModified(webRequest, response, etag)) {
            return null;
        }

        byte[] body = source;
        String responseMediaType = media.contentType();
        if (width != null) {
            try {
                var rendered = profileBannerService.renderPublicVariantPayload(source, media.contentType(), width);
                body = rendered.data();
                responseMediaType = rendered.mediaType();
            } catch (IOException | IllegalArgumentException exception) {
                throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "BANNER_PROCESSING_FAILED",
                    "Не удалось подготовить баннер."
                );
            }
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(responseMediaType))
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline; filename=profile-banner")
            .cacheControl(PUBLIC_REVALIDATED_MEDIA)
            .eTag(etag)
            .body(body);
    }

    private boolean notModified(WebRequest webRequest, HttpServletResponse response, String etag) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, PUBLIC_REVALIDATED_MEDIA.getHeaderValue());
        return webRequest.checkNotModified(etag);
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
