package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
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
@Table(name = "tournament_participants", uniqueConstraints = @UniqueConstraint(name = "uq_tournament_participant", columnNames = {"tournament_id", "user_id"}))
public class TournamentParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private boolean invited;

    @Column(nullable = false)
    private boolean participating;

    public Long getId() { return id; }
    public Tournament getTournament() { return tournament; }
    public void setTournament(Tournament tournament) { this.tournament = tournament; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public boolean isInvited() { return invited; }
    public void setInvited(boolean invited) { this.invited = invited; }
    public boolean isParticipating() { return participating; }
    public void setParticipating(boolean participating) { this.participating = participating; }
}
