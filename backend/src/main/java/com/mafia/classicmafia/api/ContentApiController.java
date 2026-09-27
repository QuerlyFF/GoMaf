package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.content.ContentActionResponse;
import com.mafia.classicmafia.api.dto.content.NewsResponse;
import com.mafia.classicmafia.api.dto.content.NewsWriteRequest;
import com.mafia.classicmafia.api.dto.content.RulesResponse;
import com.mafia.classicmafia.api.dto.content.RulesWriteRequest;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.News;
import com.mafia.classicmafia.repository.NewsRepository;
import com.mafia.classicmafia.repository.RulesPageRepository;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import com.mafia.classicmafia.service.RulesContentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ContentApiController {
    private final NewsRepository news;
    private final RulesPageRepository rules;
    private final RulesContentService rulesContentService;
    private final ModeratorPermissionService permissions;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;

    public ContentApiController(
        NewsRepository news,
        RulesPageRepository rules,
        RulesContentService rulesContentService,
        ModeratorPermissionService permissions,
        CurrentUserService currentUserService,
        AuditLogService auditLogService
    ) {
        this.news = news;
        this.rules = rules;
        this.rulesContentService = rulesContentService;
        this.permissions = permissions;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/news")
    List<NewsResponse> news(Authentication authentication) {
        boolean manageable = permissions.isAllowed(authentication, ModeratorScope.NEWS_MANAGE);
        return news.findAllByOrderByCreatedAtDesc().stream()
            .map(item -> new NewsResponse(item.getId(), item.getTitle(), item.getContent(), item.getCreatedAt(), manageable))
            .toList();
    }

    @PostMapping("/news")
    ApiDataResponse<ContentActionResponse> createNews(@RequestBody NewsWriteRequest body, Authentication authentication) {
        require(authentication, ModeratorScope.NEWS_MANAGE, "новостями");
        String title = normalize(body == null ? null : body.title());
        String content = normalize(body == null ? null : body.content());
        validateNews(title, content);
        News item = new News();
        item.setTitle(title);
        item.setContent(content);
        item = news.saveAndFlush(item);
        auditLogService.success("NEWS_CREATE", currentUserService.require(authentication), "news:" + item.getId(), "created;api");
        return new ApiDataResponse<>(new ContentActionResponse(item.getId(), "Новость опубликована."));
    }

    @PutMapping("/news/{id}")
    ApiDataResponse<ContentActionResponse> updateNews(@PathVariable Long id, @RequestBody NewsWriteRequest body, Authentication authentication) {
        require(authentication, ModeratorScope.NEWS_MANAGE, "новостями");
        String title = normalize(body == null ? null : body.title());
        String content = normalize(body == null ? null : body.content());
        validateNews(title, content);
        News item = news.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NEWS_NOT_FOUND", "Новость не найдена."));
        item.setTitle(title);
        item.setContent(content);
        news.saveAndFlush(item);
        auditLogService.success("NEWS_UPDATE", currentUserService.require(authentication), "news:" + id, "updated;api");
        return new ApiDataResponse<>(new ContentActionResponse(id, "Новость обновлена."));
    }

    @DeleteMapping("/news/{id}")
    ApiDataResponse<ContentActionResponse> deleteNews(@PathVariable Long id, Authentication authentication) {
        require(authentication, ModeratorScope.NEWS_MANAGE, "новостями");
        if (!news.existsById(id)) throw new ApiException(HttpStatus.NOT_FOUND, "NEWS_NOT_FOUND", "Новость не найдена.");
        news.deleteById(id);
        auditLogService.success("NEWS_DELETE", currentUserService.require(authentication), "news:" + id, "deleted;api");
        return new ApiDataResponse<>(new ContentActionResponse(id, "Новость удалена."));
    }

    @GetMapping("/rules")
    ApiDataResponse<RulesResponse> rules(Authentication authentication) {
        var page = rules.findById(1L).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RULES_NOT_FOUND", "Правила не найдены."));
        boolean manageable = permissions.isAllowed(authentication, ModeratorScope.RULES_MANAGE);
        return new ApiDataResponse<>(new RulesResponse(
            page.getTitle(),
            rulesContentService.parse(page.getContent()),
            manageable ? page.getContent() : null,
            manageable
        ));
    }

    @PutMapping("/rules")
    ApiDataResponse<ContentActionResponse> updateRules(@RequestBody RulesWriteRequest body, Authentication authentication) {
        require(authentication, ModeratorScope.RULES_MANAGE, "правилами");
        String title = normalize(body == null ? null : body.title());
        String content = body == null || body.content() == null ? "" : body.content().strip();
        if (title.isBlank() || title.length() > 160) throw new ApiException(HttpStatus.BAD_REQUEST, "RULES_TITLE_INVALID", "Некорректный заголовок правил.");
        if (content.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "RULES_CONTENT_REQUIRED", "Текст правил не может быть пустым.");
        if (content.length() > 150_000) throw new ApiException(HttpStatus.BAD_REQUEST, "RULES_TOO_LARGE", "Текст правил слишком большой.");
        var page = rules.findById(1L).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RULES_NOT_FOUND", "Правила не найдены."));
        page.setTitle(title);
        page.setContent(content);
        rules.saveAndFlush(page);
        auditLogService.success("RULES_UPDATE", currentUserService.require(authentication), "rules:1", "updated;api");
        return new ApiDataResponse<>(new ContentActionResponse(1L, "Правила сохранены."));
    }

    private void validateNews(String title, String content) {
        if (title.isBlank() || content.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "NEWS_REQUIRED", "Заголовок и текст новости обязательны.");
        if (title.length() > 160 || content.length() > 50_000) throw new ApiException(HttpStatus.BAD_REQUEST, "NEWS_TOO_LARGE", "Новость превышает допустимый размер.");
    }

    private void require(Authentication authentication, ModeratorScope scope, String subject) {
        try { permissions.require(authentication, scope); }
        catch (RuntimeException exception) { throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав для управления " + subject + "."); }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
