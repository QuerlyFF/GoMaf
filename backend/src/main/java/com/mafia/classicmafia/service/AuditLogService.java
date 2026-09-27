package com.mafia.classicmafia.service;

import com.mafia.classicmafia.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    public void success(String action, User actor, String target, String details) {
        write("SUCCESS", action, actor, target, details);
    }

    public void denied(String action, User actor, String target, String details) {
        write("DENIED", action, actor, target, details);
    }

    private void write(String outcome, String action, User actor, String target, String details) {
        audit.info(
            "outcome={} action={} actorId={} actor={} actorRole={} target={} details={}",
            clean(outcome),
            clean(action),
            actor == null || actor.getId() == null ? "-" : actor.getId(),
            actor == null ? "-" : clean(actor.getUsername()),
            actor == null ? "-" : actor.getRole().name(),
            clean(target),
            clean(details)
        );
    }

    private String clean(String value) {
        if (value == null) {
            return "-";
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
        }
        return sanitized.replace('=', ':');
    }
}
