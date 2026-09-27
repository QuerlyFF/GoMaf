package com.mafia.classicmafia.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CaptchaServiceTests {
    @Test
    void captchaIsRealPngAndSingleUse() throws Exception {
        CaptchaService service = new CaptchaService();
        MockHttpSession session = new MockHttpSession();
        byte[] png = service.create(session, "login");
        String answer = (String) session.getAttribute("captcha:login:code");

        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(180);
        assertThat(image.getHeight()).isEqualTo(58);
        int first = image.getRGB(0, 0);
        boolean nonUniform = false;
        for (int y = 0; y < image.getHeight() && !nonUniform; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != first) { nonUniform = true; break; }
            }
        }
        assertThat(nonUniform).isTrue();
        assertThat(answer).hasSize(5);
        assertThat(service.validate(session, "login", answer.toLowerCase())).isTrue();
        assertThat(service.validate(session, "login", answer)).isFalse();
    }
}
