package com.mafia.classicmafia;

import com.mafia.classicmafia.service.ProfileBannerService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileBannerServiceTests {
    private final ProfileBannerService service = new ProfileBannerService();

    @Test
    void acceptsGifAndPreservesAnimationCompatibleBytes() throws Exception {
        byte[] gif = gifBytes();
        var result = service.validate(new MockMultipartFile("banner", "banner.gif", "image/gif", gif));
        assertThat(result.mediaType()).isEqualTo("image/gif");
        assertThat(result.safetyVersion()).isEqualTo(1);
        assertThat(result.data()).isEqualTo(gif);
    }

    @Test
    void responsiveStaticVariantDownscalesWithoutUpscaling() throws Exception {
        byte[] source = image("png", 1920, 768);
        var compact = service.renderPublicVariantPayload(source, "image/png", 960);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compact.data()));

        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(960);
        assertThat(decoded.getHeight()).isEqualTo(384);
        assertThat(service.renderPublicVariant(compact.data(), compact.mediaType(), 1280)).isEqualTo(compact.data());
    }

    @Test
    void responsiveMobileHighDensityVariantSupports768Pixels() throws Exception {
        byte[] source = image("png", 1920, 768);
        var variant = service.renderPublicVariantPayload(source, "image/png", 768);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(variant.data()));

        assertThat(service.isSupportedPublicWidth(768)).isTrue();
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(768);
        assertThat(decoded.getHeight()).isEqualTo(307);
    }

    @Test
    void responsiveStaticVariantNeverReturnsMoreBytesThanOriginal() throws Exception {
        byte[] source = image("png", 2143, 734);
        byte[] variant = service.renderPublicVariant(source, "image/png", 1920);

        assertThat(variant.length).isLessThanOrEqualTo(source.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(variant));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isBetween(1920, 2143);
    }

    @Test
    void responsiveOpaquePngVariantUsesCompactJpegPayload() throws Exception {
        byte[] source = image("png", 1920, 768);
        var variant = service.renderPublicVariantPayload(source, "image/png", 960);

        assertThat(variant.mediaType()).isEqualTo("image/jpeg");
        assertThat(variant.data().length).isLessThan(source.length / 2);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(variant.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(960);
        assertThat(decoded.getHeight()).isEqualTo(384);
    }

    @Test
    void responsiveTransparentPngVariantPreservesPngMediaType() throws Exception {
        byte[] source = alphaPng(1000, 400);
        var variant = service.renderPublicVariantPayload(source, "image/png", 640);

        assertThat(variant.mediaType()).isEqualTo("image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(variant.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void responsiveFlatOpaquePngKeepsPngWhenLosslessVariantIsSmaller() throws Exception {
        byte[] source = flatPng(1920, 768);
        var variant = service.renderPublicVariantPayload(source, "image/png", 960);

        assertThat(variant.mediaType()).isEqualTo("image/png");
        assertThat(variant.data().length).isLessThan(source.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(variant.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(960);
    }

    @Test
    void responsiveGifVariantPreservesAnimationCompatibleBytes() throws Exception {
        byte[] gif = gifBytes();
        assertThat(service.renderPublicVariant(gif, "image/gif", 640)).isEqualTo(gif);
    }

    @Test
    void responsiveVariantRejectsUnapprovedWidth() throws Exception {
        byte[] source = image("png", 1000, 400);
        assertThatThrownBy(() -> service.renderPublicVariant(source, "image/png", 777))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ширина");
    }

    @Test
    void rejectsAnythingOverFiveMegabytesBeforeDecoding() {
        byte[] tooLarge = new byte[(int) ProfileBannerService.MAX_BANNER_BYTES + 1];
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("banner", "huge.gif", "image/gif", tooLarge)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("5 МБ");
    }


    private byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x * 31) ^ (y * 17)) & 0x00ffffff);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) throw new IllegalStateException("Image writer unavailable: " + format);
            return output.toByteArray();
        }
    }

    private byte[] flatPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int color = y < height / 2 ? 0x00172233 : 0x00d2ad65;
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    private byte[] alphaPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = 64 + ((x + y) & 0x7f);
                int rgb = ((x * 31) ^ (y * 17)) & 0x00ffffff;
                image.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    private byte[] gifBytes() throws Exception {
        BufferedImage image = new BufferedImage(30, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xd2ad65);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "gif", output);
            return output.toByteArray();
        }
    }
}
