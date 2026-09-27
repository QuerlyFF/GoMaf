package com.mafia.classicmafia.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rules_page")
public class RulesPage {
    @Id
    private Long id = 1L;

    @Column(nullable = false, length = 160)
    private String title = "Правила";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content = "";

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
