package com.mafia.classicmafia;

import com.mafia.classicmafia.service.AvatarService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarServiceTests {
    private final AvatarService service = new AvatarService();

    @Test
    void rewritesRealPngAndDropsTrailingData() throws Exception {
        byte[] original = image("png", 800, 600);
        byte[] poisoned = Arrays.copyOf(original, original.length + 64);
        Arrays.fill(poisoned, original.length, poisoned.length, (byte) 0x41);

        var result = service.validate(new MockMultipartFile("avatar", "avatar.png", "image/png", poisoned));
        BufferedImage rewritten = ImageIO.read(new ByteArrayInputStream(result.data()));

        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.safetyVersion()).isEqualTo(AvatarService.SAFETY_VERSION);
        assertThat(rewritten).isNotNull();
        assertThat(rewritten.getWidth()).isEqualTo(512);
        assertThat(rewritten.getHeight()).isEqualTo(384);
        assertThat(Arrays.copyOfRange(result.data(), Math.max(0, result.data().length - 4), result.data().length))
            .isNotEqualTo(new byte[]{0x41, 0x41, 0x41, 0x41});
    }

    @Test
    void publicListVariantDownscalesOpaqueCanonicalAvatarToCompactJpeg() throws Exception {
        byte[] source = service.validate(new MockMultipartFile("avatar", "avatar.png", "image/png", detailedPng(512, 384))).data();
        var compact = service.renderPublicVariantPayload(source, AvatarService.PUBLIC_LIST_SIDE);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compact.data()));

        assertThat(compact.mediaType()).isEqualTo("image/jpeg");
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(128);
        assertThat(decoded.getHeight()).isEqualTo(96);
        assertThat(compact.data().length).isLessThan(source.length);
    }

    @Test
    void publicProfileVariantDownscalesOpaqueCanonicalAvatarTo256Jpeg() throws Exception {
        byte[] source = service.validate(new MockMultipartFile("avatar", "avatar.png", "image/png", detailedPng(512, 384))).data();
        var compact = service.renderPublicVariantPayload(source, AvatarService.PUBLIC_PROFILE_SIDE);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compact.data()));

        assertThat(compact.mediaType()).isEqualTo("image/jpeg");
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(256);
        assertThat(decoded.getHeight()).isEqualTo(192);
        assertThat(compact.data().length).isLessThan(source.length);
    }

    @Test
    void flatOpaquePublicVariantKeepsPngWhenLosslessIsSmaller() throws Exception {
        byte[] source = service.validate(new MockMultipartFile("avatar", "avatar.png", "image/png", image("png", 512, 384))).data();
        var compact = service.renderPublicVariantPayload(source, AvatarService.PUBLIC_PROFILE_SIDE);

        assertThat(compact.mediaType()).isEqualTo("image/png");
        assertThat(compact.data().length).isLessThanOrEqualTo(source.length);
    }

    @Test
    void transparentPublicVariantPreservesPng() throws Exception {
        byte[] source = service.validate(new MockMultipartFile("avatar", "avatar.png", "image/png", transparentPng(512, 384))).data();
        var compact = service.renderPublicVariantPayload(source, AvatarService.PUBLIC_PROFILE_SIDE);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compact.data()));

        assertThat(compact.mediaType()).isEqualTo("image/png");
        assertThat(decoded).isNotNull();
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        assertThat(decoded.getRGB(10, 10) >>> 24).isLessThan(255);
    }

    @Test
    void acceptsRealJpegButStoresCanonicalPng() throws Exception {
        byte[] jpeg = image("jpg", 320, 240);
        var result = service.validate(new MockMultipartFile("avatar", "photo.jpg", "image/jpeg", jpeg));

        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.data()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
    }

    @Test
    void renamedExtensionsAreJudgedByDecodedContentAndStoredCanonically() throws Exception {
        byte[] jpeg = image("jpg", 96, 96);
        byte[] png = image("png", 96, 96);

        var jpegNamedPng = service.validate(new MockMultipartFile("avatar", "renamed.png", "image/png", jpeg));
        var pngNamedJpeg = service.validate(new MockMultipartFile("avatar", "renamed.jpg", "image/jpeg", png));

        assertThat(jpegNamedPng.mediaType()).isEqualTo("image/png");
        assertThat(pngNamedJpeg.mediaType()).isEqualTo("image/png");
        assertThat(jpegNamedPng.data()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(pngNamedJpeg.data()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
    }

    @Test
    void rejectsFakePngSignatureAndSvg() {
        byte[] fakePng = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "x.png", "image/png", fakePng)))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "x.svg", "image/svg+xml", "<svg></svg>".getBytes())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDangerousDimensionsBeforeFullUse() throws Exception {
        byte[] png = image("png", 2049, 10);
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "wide.png", "image/png", png)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2048x2048");
    }


    @Test
    void rejectsGifAndPayloadOverTwoMegabytes() throws Exception {
        byte[] gif = image("gif", 32, 32);
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "x.gif", "image/gif", gif)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PNG и JPEG");

        byte[] oversized = new byte[(int) AvatarService.MAX_AVATAR_BYTES + 1];
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "large.png", "image/png", oversized)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2 МБ");
    }

    @Test
    void acceptsExactlyTwoMegabyteDecodablePngAndStripsPadding() throws Exception {
        byte[] original = image("png", 64, 64);
        byte[] boundary = Arrays.copyOf(original, (int) AvatarService.MAX_AVATAR_BYTES);
        Arrays.fill(boundary, original.length, boundary.length, (byte) 0x42);

        var result = service.validate(new MockMultipartFile("avatar", "boundary.png", "application/octet-stream", boundary));

        assertThat(result.data().length).isLessThan((int) AvatarService.MAX_AVATAR_BYTES);
        assertThat(result.data()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(Arrays.copyOfRange(result.data(), Math.max(0, result.data().length - 4), result.data().length))
            .isNotEqualTo(new byte[]{0x42, 0x42, 0x42, 0x42});
    }

    @Test
    void rejectsPixelCountOverflowEvenWhenEachDimensionIsAllowed() throws Exception {
        byte[] png = image("png", 2048, 1954);
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "pixels.png", "image/png", png)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("4 млн пикселей");
    }

    @Test
    void rejectsTruncatedPngAndJpeg() throws Exception {
        byte[] png = Arrays.copyOf(image("png", 80, 80), 20);
        byte[] jpeg = Arrays.copyOf(image("jpg", 80, 80), 20);
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "cut.png", "image/png", png)))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "cut.jpg", "image/jpeg", jpeg)))
            .isInstanceOf(Exception.class);
    }

    @Test
    void polyglotTailNeverSurvivesCanonicalRewrite() throws Exception {
        byte[] original = image("png", 96, 96);
        byte[] marker = "<script>alert(1)</script>PK\u0003\u0004ZIP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] polyglot = Arrays.copyOf(original, original.length + marker.length);
        System.arraycopy(marker, 0, polyglot, original.length, marker.length);
        var result = service.validate(new MockMultipartFile("avatar", "polyglot.png", "text/html", polyglot));
        assertThat(new String(result.data(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("<script>", "PK\u0003\u0004ZIP");
    }

    @Test
    void rejectsZeroByteUpload() {
        assertThatThrownBy(() -> service.validate(new MockMultipartFile("avatar", "empty.png", "image/png", new byte[0])))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Выберите файл");
    }

    @Test
    void jpegExifMetadataNeverSurvivesCanonicalRewrite() throws Exception {
        byte[] jpeg = image("jpg", 96, 96);
        byte[] exif = "Exif\u0000\u0000GOMAF_PRIVATE_METADATA".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int segmentLength = exif.length + 2;
        byte[] withExif = new byte[jpeg.length + exif.length + 4];
        withExif[0] = jpeg[0]; withExif[1] = jpeg[1];
        withExif[2] = (byte) 0xFF; withExif[3] = (byte) 0xE1;
        withExif[4] = (byte) ((segmentLength >>> 8) & 0xFF);
        withExif[5] = (byte) (segmentLength & 0xFF);
        System.arraycopy(exif, 0, withExif, 6, exif.length);
        System.arraycopy(jpeg, 2, withExif, 6 + exif.length, jpeg.length - 2);

        var result = service.validate(new MockMultipartFile("avatar", "exif.jpg", "image/jpeg", withExif));
        String stored = new String(result.data(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(stored).doesNotContain("Exif", "GOMAF_PRIVATE_METADATA");
    }

    private byte[] detailedPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = x * 255 / Math.max(1, width - 1);
                int green = y * 255 / Math.max(1, height - 1);
                int blue = (x + y) & 0xff;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    private byte[] transparentPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = 96 + ((x + y) & 0x7f);
                int rgb = ((x * 31) ^ (y * 17)) & 0x00ffffff;
                image.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    private byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IllegalStateException("Image writer unavailable: " + format);
        }
        return output.toByteArray();
    }
}
