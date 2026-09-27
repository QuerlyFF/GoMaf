package com.mafia.classicmafia.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "schedule_participants", uniqueConstraints = @UniqueConstraint(name = "uq_schedule_participant", columnNames = {"schedule_entry_id", "user_id"}))
public class ScheduleParticipation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_entry_id", nullable = false)
    private ScheduleEntry scheduleEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public Long getId() { return id; }
    public ScheduleEntry getScheduleEntry() { return scheduleEntry; }
    public void setScheduleEntry(ScheduleEntry scheduleEntry) { this.scheduleEntry = scheduleEntry; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}
