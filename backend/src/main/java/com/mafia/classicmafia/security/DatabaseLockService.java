package com.mafia.classicmafia.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Service
public class DatabaseLockService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseLockService.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;

    public DatabaseLockService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgres = detectPostgres(dataSource);
        if (!postgres) {
            log.info("PostgreSQL advisory locks disabled for non-PostgreSQL datasource (test/local compatibility mode).");
        }
    }

    public void lock(String namespace, String key) {
        if (!postgres) {
            return;
        }
        long lockId = lockId(namespace + ':' + key);
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, lockId);
                statement.execute();
            }
            return null;
        });
    }

    private boolean detectPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException exception) {
            throw new IllegalStateException("Не удалось определить тип СУБД для security lock service.", exception);
        }
    }

    private long lockId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен.", exception);
        }
    }
}
