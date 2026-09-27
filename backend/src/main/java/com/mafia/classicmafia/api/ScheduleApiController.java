package com.mafia.classicmafia.api;

import com.mafia.classicmafia.api.dto.ApiDataResponse;
import com.mafia.classicmafia.api.dto.schedule.ScheduleActionResponse;
import com.mafia.classicmafia.api.dto.schedule.ScheduleEntryResponse;
import com.mafia.classicmafia.api.dto.schedule.ScheduleParticipantResponse;
import com.mafia.classicmafia.api.dto.schedule.ScheduleWriteRequest;
import com.mafia.classicmafia.entity.MafiaFormat;
import com.mafia.classicmafia.entity.ModeratorScope;
import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.ScheduleEntry;
import com.mafia.classicmafia.entity.ScheduleEntryType;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.ScheduleEntryRepository;
import com.mafia.classicmafia.repository.ScheduleParticipationRepository;
import com.mafia.classicmafia.repository.projection.ScheduleParticipantProjection;
import com.mafia.classicmafia.service.AuditLogService;
import com.mafia.classicmafia.service.CurrentUserService;
import com.mafia.classicmafia.service.ModeratorPermissionService;
import com.mafia.classicmafia.service.ScheduleParticipationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedule")
public class ScheduleApiController {
    private final ScheduleEntryRepository schedule;
    private final ScheduleParticipationRepository scheduleParticipants;
    private final ModeratorPermissionService permissions;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final ScheduleParticipationService participationService;

    public ScheduleApiController(
        ScheduleEntryRepository schedule,
        ScheduleParticipationRepository scheduleParticipants,
        ModeratorPermissionService permissions,
        CurrentUserService currentUserService,
        AuditLogService auditLogService,
        ScheduleParticipationService participationService
    ) {
        this.schedule = schedule;
        this.scheduleParticipants = scheduleParticipants;
        this.permissions = permissions;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        this.participationService = participationService;
    }

    @GetMapping
    List<ScheduleEntryResponse> list(
        Authentication authentication,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        if ((from == null) != (to == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_RANGE_INVALID", "Параметры from и to должны передаваться вместе.");
        }
        if (from != null && (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_RANGE_INVALID", "Диапазон расписания должен быть положительным и не больше 31 дня.");
        }

        Long currentUserId = currentUserId(authentication);
        boolean manageable = permissions.isAllowed(authentication, ModeratorScope.SCHEDULE_MANAGE);
        List<ScheduleEntry> entries = from == null
            ? schedule.findAllByOrderByScheduledAtAsc()
            : schedule.findAllByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(from, to);

        Map<Long, List<ScheduleParticipantProjection>> participantsByEntry = participantRows(entries);
        return entries.stream()
            .map(entry -> toResponse(entry, participantsByEntry.getOrDefault(entry.getId(), List.of()), currentUserId, manageable))
            .toList();
    }

    @PostMapping
    ApiDataResponse<ScheduleActionResponse> create(@RequestBody ScheduleWriteRequest body, Authentication authentication) {
        requireManage(authentication);
        ScheduleEntryType type = parseType(body == null ? null : body.entryType());
        ScheduleEntry entry = save(new ScheduleEntry(), type, body);
        User actor = currentUserService.require(authentication);
        auditLogService.success("SCHEDULE_CREATE", actor, "schedule:" + entry.getId(), "type:" + type.name() + ";api");
        return new ApiDataResponse<>(new ScheduleActionResponse(entry.getId(), false, type == ScheduleEntryType.EVENT ? "Мероприятие сохранено." : "Игра в расписании сохранена."));
    }

    @PutMapping("/{id}")
    ApiDataResponse<ScheduleActionResponse> update(@PathVariable Long id, @RequestBody ScheduleWriteRequest body, Authentication authentication) {
        requireManage(authentication);
        ScheduleEntry entry = requireEntry(id);
        ScheduleEntryType type = entry.getEntryType();
        if (body != null && body.entryType() != null && !body.entryType().isBlank()) type = parseType(body.entryType());
        entry = save(entry, type, body);
        auditLogService.success("SCHEDULE_UPDATE", currentUserService.require(authentication), "schedule:" + entry.getId(), "type:" + type.name() + ";api");
        return new ApiDataResponse<>(new ScheduleActionResponse(entry.getId(), false, type == ScheduleEntryType.EVENT ? "Мероприятие обновлено." : "Игра в расписании обновлена."));
    }

    @DeleteMapping("/{id}")
    ApiDataResponse<ScheduleActionResponse> delete(@PathVariable Long id, Authentication authentication) {
        requireManage(authentication);
        requireEntry(id);
        schedule.deleteById(id);
        auditLogService.success("SCHEDULE_DELETE", currentUserService.require(authentication), "schedule:" + id, "deleted;api");
        return new ApiDataResponse<>(new ScheduleActionResponse(id, false, "Запись расписания удалена."));
    }

    @PostMapping("/{id}/participation")
    ApiDataResponse<ScheduleActionResponse> toggle(@PathVariable Long id, Authentication authentication) {
        ScheduleEntry entry = requireEntry(id);
        if (!entry.isGame()) throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_NOT_GAME", "Участвовать можно только в игре.");
        User user = currentUserService.require(authentication);
        if (!user.getRole().isPublicPlayer()) throw new ApiException(HttpStatus.FORBIDDEN, "PARTICIPATION_FORBIDDEN", "Системный аккаунт не может участвовать в игре.");
        boolean participating = participationService.toggle(id, user);
        String message = participating ? "Вы добавлены в список участников." : "Участие отменено.";
        return new ApiDataResponse<>(new ScheduleActionResponse(id, participating, message));
    }

    private ScheduleEntry save(ScheduleEntry entry, ScheduleEntryType type, ScheduleWriteRequest body) {
        if (body == null) throw new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_INVALID", "Данные расписания обязательны.");
        String title = body.title() == null ? "" : body.title().strip();
        String host = body.host() == null ? "" : body.host().strip();
        String description = body.description() == null ? "" : body.description().strip();
        if (title.isBlank() || title.length() > 120) throw new ApiException(HttpStatus.BAD_REQUEST, "TITLE_INVALID", type == ScheduleEntryType.EVENT ? "Укажите название мероприятия." : "Укажите название игры.");
        if (body.scheduledAt() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "DATE_REQUIRED", "Укажите дату и время.");
        if (host.isBlank() || host.length() > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "HOST_INVALID", "Укажите, кто проводит.");
        if (description.length() > 10_000) throw new ApiException(HttpStatus.BAD_REQUEST, "DESCRIPTION_TOO_LONG", "Описание слишком длинное.");
        MafiaFormat format = type == ScheduleEntryType.GAME ? parseFormat(body.mafiaFormat()) : MafiaFormat.CLASSIC;
        entry.setEntryType(type);
        entry.setTitle(title);
        entry.setScheduledAt(body.scheduledAt());
        entry.setHost(host);
        entry.setDescription(description);
        entry.setRated(type == ScheduleEntryType.GAME && body.rated());
        entry.setMafiaFormat(format);
        return schedule.saveAndFlush(entry);
    }

    private ScheduleEntryResponse toResponse(
        ScheduleEntry entry,
        List<ScheduleParticipantProjection> participantRows,
        Long currentUserId,
        boolean manageable
    ) {
        List<ScheduleParticipantResponse> publicParticipants = participantRows.stream()
            .filter(row -> row.role().isPublicPlayer())
            .map(row -> new ScheduleParticipantResponse(row.userId(), row.username()))
            .toList();
        boolean participating = currentUserId != null && participantRows.stream()
            .anyMatch(row -> row.userId().equals(currentUserId));
        return new ScheduleEntryResponse(
            entry.getId(), entry.getEntryType().name(), entry.getTitle(), entry.getScheduledAt(), entry.getHost(), entry.getDescription(),
            entry.isRated(), entry.getMafiaFormat().name(), displayFormat(entry.getMafiaFormat()), publicParticipants,
            participating, manageable
        );
    }

    private Map<Long, List<ScheduleParticipantProjection>> participantRows(List<ScheduleEntry> entries) {
        if (entries.isEmpty()) return Map.of();
        List<Long> ids = entries.stream().map(ScheduleEntry::getId).toList();
        Map<Long, List<ScheduleParticipantProjection>> result = new HashMap<>();
        for (ScheduleParticipantProjection row : scheduleParticipants.findPublicParticipantRows(ids)) {
            result.computeIfAbsent(row.scheduleEntryId(), ignored -> new java.util.ArrayList<>()).add(row);
        }
        return result;
    }

    private String displayFormat(MafiaFormat format) {
        return format == MafiaFormat.CITY ? "Городская мафия" : "Классическая мафия";
    }

    private ScheduleEntry requireEntry(Long id) {
        return schedule.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "Запись расписания не найдена."));
    }

    private ScheduleEntryType parseType(String value) {
        try { return ScheduleEntryType.valueOf(value == null ? "" : value); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "ENTRY_TYPE_INVALID", "Некорректный тип записи расписания."); }
    }

    private MafiaFormat parseFormat(String value) {
        try { return MafiaFormat.valueOf(value == null || value.isBlank() ? "CLASSIC" : value); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "MAFIA_FORMAT_INVALID", "Некорректный формат мафии."); }
    }

    private void requireManage(Authentication authentication) {
        try { permissions.require(authentication, ModeratorScope.SCHEDULE_MANAGE); }
        catch (RuntimeException exception) { throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав для управления расписанием."); }
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try { return currentUserService.requireSecurity(authentication).id(); }
        catch (RuntimeException exception) { return null; }
    }
}
