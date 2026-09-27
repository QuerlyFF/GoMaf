package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.ApiPageResponse;
import com.mafia.classicmafia.api.dto.PlayerGameResponse;
import com.mafia.classicmafia.api.dto.ProfileActionResponse;
import com.mafia.classicmafia.api.dto.ProfileAboutUpdateRequest;
import com.mafia.classicmafia.api.dto.SelfProfileResponse;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.UserRepository;
import com.mafia.classicmafia.repository.projection.SelfProfileProjection;
import com.mafia.classicmafia.security.ClientIpResolver;
import com.mafia.classicmafia.security.IpHashService;
import com.mafia.classicmafia.security.RateLimitService;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.AvatarService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.PlayerReadService;
import com.mafia.classicmafia.service.ProfileBannerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileApiController {
    private final UserRepository users;
    private final CurrentUserService currentUserService;
    private final PlayerReadService playerReadService;
    private final AvatarService avatarService;
    private final ProfileBannerService profileBannerService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final IpHashService ipHashService;
    private final AuditLogService auditLogService;

    public ProfileApiController(
        UserRepository users,
        CurrentUserService currentUserService,
        PlayerReadService playerReadService,
        AvatarService avatarService,
        ProfileBannerService profileBannerService,
        RateLimitService rateLimitService,
        ClientIpResolver clientIpResolver,
        IpHashService ipHashService,
        AuditLogService auditLogService
    ) {
        this.users = users;
        this.currentUserService = currentUserService;
        this.playerReadService = playerReadService;
        this.avatarService = avatarService;
        this.profileBannerService = profileBannerService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.ipHashService = ipHashService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    ApiDataResponse<SelfProfileResponse> profile(Authentication authentication) {
        SelfProfileProjection user = currentUserService.requireSelfProfile(authentication);
        return new ApiDataResponse<>(toResponse(user));
    }

    @GetMapping("/games")
    ApiPageResponse<PlayerGameResponse> games(
        Authentication authentication,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Integer size
    ) {
        return playerReadService.getSelfGames(authentication, page, size);
    }

    @GetMapping("/avatar")
    ResponseEntity<?> avatar(Authentication authentication) {
        User user = currentUserService.require(authentication);
        if (!hasSafeAvatar(user)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/css/default-avatar.svg"))
                .cacheControl(CacheControl.noStore())
                .build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "inline; filename=avatar.png")
            .cacheControl(CacheControl.noStore())
            .body(user.getAvatarData());
    }

    @PatchMapping("/about")
    ApiDataResponse<ProfileActionResponse> updateAbout(
        Authentication authentication,
        @RequestBody ProfileAboutUpdateRequest body
    ) {
        User user = currentUserService.require(authentication);
        user.setAboutMe(normalizeAbout(body == null ? null : body.aboutMe()));
        users.saveAndFlush(user);
        auditLogService.success("PROFILE_ABOUT_UPDATE", user, "self", "updated");
        return new ApiDataResponse<>(new ProfileActionResponse("Текст профиля сохранён."));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiDataResponse<ProfileActionResponse> uploadAvatar(
        Authentication authentication,
        @RequestParam("avatar") MultipartFile avatar,
        HttpServletRequest request
    ) {
        User user = currentUserService.require(authentication);
        String ipHash = ipHashService.hash(clientIpResolver.resolve(request));
        if (!rateLimitService.allowAvatarUpload(ipHash, user.getUsername())) {
            auditLogService.denied("PROFILE_AVATAR_UPLOAD", user, "self", "rate-limit");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AVATAR_RATE_LIMITED", "Слишком много загрузок аватара. Попробуйте позже.");
        }
        try {
            AvatarService.ValidatedAvatar validated = avatarService.validate(avatar);
            user.setAvatarData(validated.data());
            user.setAvatarContentType(validated.mediaType());
            user.setAvatarSafetyVersion(validated.safetyVersion());
            users.saveAndFlush(user);
            auditLogService.success("PROFILE_AVATAR_UPLOAD", user, "self", "sanitized");
            return new ApiDataResponse<>(new ProfileActionResponse("Аватар обновлён и безопасно обработан."));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_INVALID", "Файл не соответствует требованиям к аватару.");
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_PROCESSING_FAILED", "Не удалось безопасно обработать изображение.");
        }
    }

    @PostMapping(value = "/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiDataResponse<ProfileActionResponse> uploadBanner(
        Authentication authentication,
        @RequestParam("banner") MultipartFile banner,
        HttpServletRequest request
    ) {
        User user = currentUserService.require(authentication);
        String ipHash = ipHashService.hash(clientIpResolver.resolve(request));
        if (!rateLimitService.allowBannerUpload(ipHash, user.getUsername())) {
            auditLogService.denied("PROFILE_BANNER_UPLOAD", user, "self", "rate-limit");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BANNER_RATE_LIMITED", "Слишком много загрузок баннера. Попробуйте позже.");
        }
        try {
            ProfileBannerService.ValidatedBanner validated = profileBannerService.validate(banner);
            user.setProfileBannerData(validated.data());
            user.setProfileBannerContentType(validated.mediaType());
            user.setProfileBannerSafetyVersion(validated.safetyVersion());
            users.saveAndFlush(user);
            auditLogService.success("PROFILE_BANNER_UPLOAD", user, "self", "validated:" + validated.mediaType());
            return new ApiDataResponse<>(new ProfileActionResponse("Баннер профиля обновлён."));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BANNER_INVALID", "Файл не соответствует требованиям к баннеру.");
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BANNER_PROCESSING_FAILED", "Не удалось проверить изображение баннера.");
        }
    }

    @DeleteMapping("/banner")
    ApiDataResponse<ProfileActionResponse> resetBanner(Authentication authentication) {
        User user = currentUserService.require(authentication);
        user.setProfileBannerData(null);
        user.setProfileBannerContentType(null);
        user.setProfileBannerSafetyVersion(null);
        users.saveAndFlush(user);
        auditLogService.success("PROFILE_BANNER_RESET", user, "self", "banner-cleared");
        return new ApiDataResponse<>(new ProfileActionResponse("Баннер профиля сброшен."));
    }

    @DeleteMapping("/avatar")
    ApiDataResponse<ProfileActionResponse> resetAvatar(Authentication authentication) {
        User user = currentUserService.require(authentication);
        user.setAvatarData(null);
        user.setAvatarContentType(null);
        user.setAvatarSafetyVersion(null);
        users.saveAndFlush(user);
        auditLogService.success("PROFILE_AVATAR_RESET", user, "self", "avatar-cleared");
        return new ApiDataResponse<>(new ProfileActionResponse("Аватар сброшен."));
    }

    private SelfProfileResponse toResponse(SelfProfileProjection user) {
        double winRate = user.gamesPlayed() == 0 ? 0.0 : (double) user.wins() / user.gamesPlayed();
        return new SelfProfileResponse(
            user.id(),
            user.username(),
            user.role().name(),
            user.aboutMe(),
            user.gamesPlayed(),
            user.wins(),
            user.losses(),
            winRate,
            user.averageScore(),
            user.rating(),
            "/api/v1/profile/avatar",
            "/api/v1/players/" + user.id() + "/banner",
            "/api/v1/profile/games"
        );
    }

    private String normalizeAbout(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ABOUT_TOO_LONG", "Текст о себе должен быть не длиннее 500 символов.");
        }
        return normalized;
    }

    private boolean hasSafeAvatar(User user) {
        return user.getAvatarData() != null
            && user.getAvatarData().length > 0
            && Integer.valueOf(AvatarService.SAFETY_VERSION).equals(user.getAvatarSafetyVersion())
            && "image/png".equals(user.getAvatarContentType());
    }
}
