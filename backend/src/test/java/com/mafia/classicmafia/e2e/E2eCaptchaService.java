package com.mafia.classicmafia.e2e;

import com.mafia.classicmafia.security.CaptchaService;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

@Component
@Primary
@Profile("e2e")
public class E2eCaptchaService extends CaptchaService {
    private static final String CODE = "ABCDE";
    private static final Set<String> PURPOSES = Set.of("login", "register");
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );

    @Override
    public byte[] create(HttpSession session, String purpose) throws IOException {
        String normalized = normalizePurpose(purpose);
        session.setAttribute(key(normalized), CODE);
        return PNG.clone();
    }

    @Override
    public boolean validate(HttpSession session, String purpose, String answer) {
        final String normalized;
        try {
            normalized = normalizePurpose(purpose);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        Object expected = session.getAttribute(key(normalized));
        session.removeAttribute(key(normalized));
        String provided = answer == null ? "" : answer.strip().toUpperCase(Locale.ROOT);
        return CODE.equals(expected) && CODE.equals(provided);
    }

    private String normalizePurpose(String purpose) {
        String normalized = purpose == null ? "" : purpose.toLowerCase(Locale.ROOT);
        if (!PURPOSES.contains(normalized)) {
            throw new IllegalArgumentException("Unknown E2E CAPTCHA purpose");
        }
        return normalized;
    }

    private String key(String purpose) {
        return "e2e:captcha:" + purpose;
    }
}
