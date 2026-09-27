package com.mafia.classicmafia.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tournaments")
public class Tournament {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private int gameCount;

    @Column(precision = 10, scale = 2)
    private BigDecimal participationCost;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("eventDate ASC, id ASC")
    private List<TournamentDate> dates = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getGameCount() { return gameCount; }
    public void setGameCount(int gameCount) { this.gameCount = gameCount; }
    public BigDecimal getParticipationCost() { return participationCost; }
    public void setParticipationCost(BigDecimal participationCost) { this.participationCost = participationCost; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<TournamentDate> getDates() { return dates; }

    public void replaceDates(List<LocalDate> values) {
        dates.clear();
        int position = 0;
        for (LocalDate value : values) {
            TournamentDate item = new TournamentDate();
            item.setTournament(this);
            item.setEventDate(value);
            item.setPosition(position++);
            dates.add(item);
        }
    }

    public boolean isPaid() {
        return participationCost != null && participationCost.signum() > 0;
    }

    public boolean isCompleted() {
        LocalDate today = LocalDate.now();
        return !dates.isEmpty() && dates.stream().map(TournamentDate::getEventDate).allMatch(date -> date.isBefore(today));
    }

    public String getParticipationCostFormatted() {
        if (!isPaid()) return "Без стоимости";
        return participationCost.stripTrailingZeros().toPlainString();
    }
}
