package com.mafia.classicmafia.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Service
public class AvatarService {
    public static final long MAX_AVATAR_BYTES = 2L * 1024L * 1024L;
    public static final int MAX_SOURCE_WIDTH = 2048;
    public static final int MAX_SOURCE_HEIGHT = 2048;
    public static final long MAX_SOURCE_PIXELS = 4_000_000L;
    public static final int MAX_STORED_SIDE = 512;
    public static final int PUBLIC_LIST_SIDE = 128;
    public static final int PUBLIC_PROFILE_SIDE = 256;
    public static final int PUBLIC_VARIANT_VERSION = 3;
    public static final int SAFETY_VERSION = 1;
    private static final float OPAQUE_JPEG_QUALITY = 0.90f;

    private static final Set<String> ALLOWED_FORMATS = Set.of("png", "jpeg", "jpg");

    static {
        ImageIO.setUseCache(false);
    }

    public ValidatedAvatar validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Выберите файл изображения.");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Размер изображения не должен превышать 2 МБ.");
        }

        byte[] source = file.getBytes();
        if (source.length == 0 || source.length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Размер изображения не должен превышать 2 МБ.");
        }

        DecodedImage decoded = decode(source);
        BufferedImage sanitized = rewrite(decoded.image(), MAX_STORED_SIDE);
        byte[] safeBytes = encodePng(sanitized);
        if (safeBytes.length == 0 || safeBytes.length > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("После безопасной обработки изображение получилось слишком большим.");
        }
        return new ValidatedAvatar(safeBytes, "image/png", SAFETY_VERSION);
    }

    private DecodedImage decode(byte[] source) throws IOException {
        try (
            ByteArrayInputStream bytes = new ByteArrayInputStream(source);
            ImageInputStream input = ImageIO.createImageInputStream(bytes)
        ) {
            if (input == null) {
                throw new IllegalArgumentException("Файл не является поддерживаемым изображением.");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Разрешены только настоящие PNG и JPEG изображения.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new IllegalArgumentException("Разрешены только PNG и JPEG изображения.");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (width < 1 || height < 1 || width > MAX_SOURCE_WIDTH || height > MAX_SOURCE_HEIGHT || pixels > MAX_SOURCE_PIXELS) {
                    throw new IllegalArgumentException("Разрешение аватара не должно превышать 2048x2048 и 4 млн пикселей.");
                }

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("Изображение не удалось декодировать.");
                }
                return new DecodedImage(image);
            } catch (IndexOutOfBoundsException | javax.imageio.IIOException exception) {
                throw new IllegalArgumentException("Изображение повреждено или имеет неподдерживаемую структуру.", exception);
            } finally {
                reader.dispose();
            }
        }
    }

    public byte[] renderPublicVariant(byte[] sanitizedPng, int maxSide) throws IOException {
        return renderPublicVariantPayload(sanitizedPng, maxSide).data();
    }

    public RenderedAvatar renderPublicVariantPayload(byte[] sanitizedPng, int maxSide) throws IOException {
        if (sanitizedPng == null || sanitizedPng.length == 0) {
            throw new IllegalArgumentException("Аватар отсутствует.");
        }
        if (maxSide != PUBLIC_LIST_SIDE && maxSide != PUBLIC_PROFILE_SIDE && maxSide != MAX_STORED_SIDE) {
            throw new IllegalArgumentException("Неподдерживаемый размер варианта аватара.");
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(sanitizedPng));
        if (source == null) {
            throw new IOException("Сохранённый PNG аватара не декодируется.");
        }

        boolean transparent = hasTransparency(source);
        BufferedImage target = rewrite(source, maxSide, transparent);
        byte[] lossless = target == source ? sanitizedPng : encodePng(target);
        if (!transparent) {
            byte[] jpeg = writeJpeg(target, OPAQUE_JPEG_QUALITY);
            if (jpeg.length * 5L < lossless.length * 4L) {
                return new RenderedAvatar(jpeg, "image/jpeg");
            }
        }
        return new RenderedAvatar(lossless, "image/png");
    }

    public byte[] renderPublicListVariant(byte[] sanitizedPng) throws IOException {
        return renderPublicVariant(sanitizedPng, PUBLIC_LIST_SIDE);
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG encoder недоступен.");
            }
            return output.toByteArray();
        }
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG encoder недоступен.");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private boolean hasTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) != 0xff) {
                    return true;
                }
            }
        }
        return false;
    }

    private BufferedImage rewrite(BufferedImage source, int maxSide) {
        return rewrite(source, maxSide, true);
    }

    private BufferedImage rewrite(BufferedImage source, int maxSide, boolean transparent) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        if (scale == 1.0 && (transparent || source.getType() == BufferedImage.TYPE_INT_RGB)) {
            return source;
        }

        BufferedImage target = new BufferedImage(
            targetWidth,
            targetHeight,
            transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private record DecodedImage(BufferedImage image) {
    }

    public record ValidatedAvatar(byte[] data, String mediaType, int safetyVersion) {
    }

    public record RenderedAvatar(byte[] data, String mediaType) {
    }
}
