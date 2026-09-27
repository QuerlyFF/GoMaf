package com.mafia.classicmafia.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "games")
public class Game {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private LocalDate gameDate;

    @Column(nullable = false, length = 80)
    private String host;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GameWinner winner;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary = "";

    @Column(name = "hidden_from_public", nullable = false)
    private boolean hiddenFromPublic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id")
    private Tournament tournament;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seatNumber ASC")
    private List<GamePlayer> players = new ArrayList<>();

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<GameVideo> videos = new ArrayList<>();

    @ManyToMany
    @JoinTable(
        name = "game_visibility_users",
        joinColumns = @JoinColumn(name = "game_id"),
        inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private List<User> hiddenAllowedUsers = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getGameDate() { return gameDate; }
    public void setGameDate(LocalDate gameDate) { this.gameDate = gameDate; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public GameWinner getWinner() { return winner; }
    public void setWinner(GameWinner winner) { this.winner = winner; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public boolean isHiddenFromPublic() { return hiddenFromPublic; }
    public void setHiddenFromPublic(boolean hiddenFromPublic) { this.hiddenFromPublic = hiddenFromPublic; }
    public Tournament getTournament() { return tournament; }
    public void setTournament(Tournament tournament) { this.tournament = tournament; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<GamePlayer> getPlayers() { return players; }
    public List<GameVideo> getVideos() { return videos; }
    public List<User> getHiddenAllowedUsers() { return hiddenAllowedUsers; }

    public void addPlayer(GamePlayer player) { player.setGame(this); players.add(player); }
    public void clearPlayers() { players.clear(); }

    public void replaceVideos(List<String> urls) {
        videos.clear();
        int position = 0;
        for (String url : urls) {
            GameVideo video = new GameVideo();
            video.setGame(this);
            video.setPosition(position++);
            video.setUrl(url);
            videos.add(video);
        }
    }

    public void replaceHiddenAllowedUsers(List<User> users) {
        hiddenAllowedUsers.clear();
        hiddenAllowedUsers.addAll(users);
    }

    public String getGameDateFormatted() { return gameDate == null ? "" : gameDate.format(DISPLAY_DATE); }
}
