package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(name = "username_key", nullable = false, unique = true, length = 32)
    private String usernameKey;

    @Column(nullable = false, length = 100)
    private String password;

    /**
     * Logical v10 role. The legacy `role` column is deliberately kept separately
     * so a code rollback to v9 still sees MODERATOR accounts as PLAYER.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_role", nullable = false, length = 16)
    private Role role = Role.PLAYER;

    @Column(name = "role", nullable = false, length = 16)
    private String legacyRole = Role.PLAYER.name();

    @Column(name = "avatar_data", columnDefinition = "bytea")
    private byte[] avatarData;

    @Column(length = 32)
    private String avatarContentType;

    @Column(name = "avatar_safety_version")
    private Integer avatarSafetyVersion;

    @Column(name = "profile_banner_data", columnDefinition = "bytea")
    private byte[] profileBannerData;

    @Column(name = "profile_banner_content_type", length = 32)
    private String profileBannerContentType;

    @Column(name = "profile_banner_safety_version")
    private Integer profileBannerSafetyVersion;

    @Column(name = "registration_ip_hash", length = 64)
    private String registrationIpHash;

    @Column(name = "credential_version", nullable = false)
    private Integer credentialVersion = 0;

    @Column(name = "authorization_version", nullable = false)
    private Integer authorizationVersion = 0;

    @Column(nullable = false)
    private int gamesPlayed = 0;

    @Column(nullable = false)
    private int wins = 0;

    @Column(nullable = false)
    private int losses = 0;

    @Column(nullable = false)
    private double rating = 1000.0;

    @Column(nullable = false)
    private double averageScore = 0.0;

    @Column(name = "about_me", nullable = false, columnDefinition = "TEXT")
    private String aboutMe = "";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
        this.usernameKey = canonicalUsername(username);
    }

    public String getUsernameKey() {
        return usernameKey;
    }

    public void setUsernameKey(String usernameKey) {
        this.usernameKey = usernameKey;
    }

    public static String canonicalUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    @PreUpdate
    void synchronizeCompatibilityColumns() {
        this.usernameKey = canonicalUsername(this.username);
        this.legacyRole = role == Role.ADMIN ? Role.ADMIN.name() : Role.PLAYER.name();
        this.credentialVersion = Math.max(0, getCredentialVersion());
        this.authorizationVersion = Math.max(0, getAuthorizationVersion());
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role == null ? Role.PLAYER : role;
        this.legacyRole = this.role == Role.ADMIN ? Role.ADMIN.name() : Role.PLAYER.name();
    }

    public String getLegacyRole() {
        return legacyRole;
    }

    public byte[] getAvatarData() {
        return avatarData;
    }

    public void setAvatarData(byte[] avatarData) {
        this.avatarData = avatarData;
    }

    public String getAvatarContentType() {
        return avatarContentType;
    }

    public void setAvatarContentType(String avatarContentType) {
        this.avatarContentType = avatarContentType;
    }

    public Integer getAvatarSafetyVersion() {
        return avatarSafetyVersion;
    }

    public void setAvatarSafetyVersion(Integer avatarSafetyVersion) {
        this.avatarSafetyVersion = avatarSafetyVersion;
    }

    public byte[] getProfileBannerData() {
        return profileBannerData;
    }

    public void setProfileBannerData(byte[] profileBannerData) {
        this.profileBannerData = profileBannerData;
    }

    public String getProfileBannerContentType() {
        return profileBannerContentType;
    }

    public void setProfileBannerContentType(String profileBannerContentType) {
        this.profileBannerContentType = profileBannerContentType;
    }

    public Integer getProfileBannerSafetyVersion() {
        return profileBannerSafetyVersion;
    }

    public void setProfileBannerSafetyVersion(Integer profileBannerSafetyVersion) {
        this.profileBannerSafetyVersion = profileBannerSafetyVersion;
    }

    public String getRegistrationIpHash() {
        return registrationIpHash;
    }

    public void setRegistrationIpHash(String registrationIpHash) {
        this.registrationIpHash = registrationIpHash;
    }

    public int getCredentialVersion() {
        return credentialVersion == null ? 0 : credentialVersion;
    }

    public void setCredentialVersion(Integer credentialVersion) {
        this.credentialVersion = credentialVersion == null ? 0 : Math.max(0, credentialVersion);
    }

    public void incrementCredentialVersion() {
        this.credentialVersion = getCredentialVersion() + 1;
    }

    public int getAuthorizationVersion() {
        return authorizationVersion == null ? 0 : authorizationVersion;
    }

    public void setAuthorizationVersion(Integer authorizationVersion) {
        this.authorizationVersion = authorizationVersion == null ? 0 : Math.max(0, authorizationVersion);
    }

    public void incrementAuthorizationVersion() {
        this.authorizationVersion = getAuthorizationVersion() + 1;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(int gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public int getLosses() {
        return losses;
    }

    public void setLosses(int losses) {
        this.losses = losses;
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(double averageScore) {
        this.averageScore = averageScore;
    }

    public String getAboutMe() {
        return aboutMe == null ? "" : aboutMe;
    }

    public void setAboutMe(String aboutMe) {
        this.aboutMe = aboutMe == null ? "" : aboutMe;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
