package com.mafia.classicmafia.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "migration")
@Order(Ordered.LOWEST_PRECEDENCE)
public class MigrationExitRunner implements ApplicationRunner {
    private final ConfigurableApplicationContext context;

    public MigrationExitRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = SpringApplication.exit(context);
        if (exitCode != 0) {
            throw new IllegalStateException("Migration application returned non-zero exit code: " + exitCode);
        }
    }
}
