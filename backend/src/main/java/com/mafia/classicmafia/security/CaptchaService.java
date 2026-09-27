package com.mafia.classicmafia.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Service
public class CaptchaService {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Set<String> PURPOSES = Set.of("login", "register");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int WIDTH = 180;
    private static final int HEIGHT = 58;

    private final SecureRandom random = new SecureRandom();

    public byte[] create(HttpSession session, String purpose) throws IOException {
        String safePurpose = purpose(purpose);
        String code = randomCode();
        session.setAttribute(codeKey(safePurpose), code);
        session.setAttribute(timeKey(safePurpose), System.currentTimeMillis());

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(17, 20, 27));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 7; i++) {
                graphics.setColor(new Color(70 + random.nextInt(70), 70 + random.nextInt(70), 70 + random.nextInt(70)));
                graphics.setStroke(new BasicStroke(1f + random.nextFloat()));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 31));
            for (int i = 0; i < code.length(); i++) {
                int x = 16 + i * 31 + random.nextInt(5);
                int y = 37 + random.nextInt(10);
                graphics.rotate(Math.toRadians(random.nextInt(15) - 7), x + 10, y - 10);
                graphics.setColor(i % 2 == 0 ? new Color(241, 243, 247) : new Color(201, 163, 90));
                graphics.drawString(String.valueOf(code.charAt(i)), x, y);
                graphics.setTransform(new java.awt.geom.AffineTransform());
            }

            for (int i = 0; i < 80; i++) {
                graphics.setColor(new Color(90 + random.nextInt(90), 90 + random.nextInt(90), 90 + random.nextInt(90)));
                graphics.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG encoder недоступен.");
            }
            return output.toByteArray();
        }
    }

    public boolean validate(HttpSession session, String purpose, String answer) {
        String safePurpose;
        try {
            safePurpose = purpose(purpose);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        Object expectedObject = session.getAttribute(codeKey(safePurpose));
        Object issuedAtObject = session.getAttribute(timeKey(safePurpose));
        session.removeAttribute(codeKey(safePurpose));
        session.removeAttribute(timeKey(safePurpose));

        if (!(expectedObject instanceof String expected) || !(issuedAtObject instanceof Long issuedAt)) {
            return false;
        }
        if (System.currentTimeMillis() - issuedAt > TTL.toMillis()) {
            return false;
        }
        String normalized = answer == null ? "" : answer.strip().toUpperCase(Locale.ROOT);
        return expected.equals(normalized);
    }

    private String randomCode() {
        StringBuilder result = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }

    private String purpose(String purpose) {
        String normalized = purpose == null ? "" : purpose.toLowerCase(Locale.ROOT);
        if (!PURPOSES.contains(normalized)) {
            throw new IllegalArgumentException("Неизвестный тип CAPTCHA.");
        }
        return normalized;
    }

    private String codeKey(String purpose) {
        return "captcha:" + purpose + ":code";
    }

    private String timeKey(String purpose) {
        return "captcha:" + purpose + ":issued";
    }
}
