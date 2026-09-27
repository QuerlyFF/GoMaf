package com.mafia.classicmafia.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "game_schedule")
public class ScheduleEntry {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter INPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private ScheduleEntryType entryType = ScheduleEntryType.GAME;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    @Column(nullable = false, length = 80)
    private String host;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    @Column(nullable = false)
    private boolean rated = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "mafia_format", nullable = false, length = 16)
    private MafiaFormat mafiaFormat = MafiaFormat.CLASSIC;

    @OneToMany(mappedBy = "scheduleEntry", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<ScheduleParticipation> participants = new ArrayList<>();

    public Long getId() { return id; }
    public ScheduleEntryType getEntryType() { return entryType; }
    public void setEntryType(ScheduleEntryType entryType) { this.entryType = entryType == null ? ScheduleEntryType.GAME : entryType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isRated() { return rated; }
    public void setRated(boolean rated) { this.rated = rated; }
    public MafiaFormat getMafiaFormat() { return mafiaFormat; }
    public void setMafiaFormat(MafiaFormat mafiaFormat) { this.mafiaFormat = mafiaFormat == null ? MafiaFormat.CLASSIC : mafiaFormat; }
    public List<ScheduleParticipation> getParticipants() { return participants; }
    public boolean isGame() { return entryType == ScheduleEntryType.GAME; }
    public boolean isEvent() { return entryType == ScheduleEntryType.EVENT; }
    public boolean hasParticipant(Long userId) { return userId != null && participants.stream().anyMatch(item -> item.getUser().getId().equals(userId)); }

    public String getScheduledAtFormatted() { return scheduledAt == null ? "" : scheduledAt.format(DISPLAY_FORMAT); }
    public String getScheduledAtInputValue() { return scheduledAt == null ? "" : scheduledAt.format(INPUT_FORMAT); }
}
