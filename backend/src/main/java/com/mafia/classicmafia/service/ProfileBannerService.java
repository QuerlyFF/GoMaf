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
import java.util.Map;
import java.util.Set;

@Service
public class ProfileBannerService {
    public static final long MAX_BANNER_BYTES = 5L * 1024L * 1024L;
    public static final int MAX_WIDTH = 3000;
    public static final int MAX_HEIGHT = 1500;
    public static final long MAX_FRAME_PIXELS = 4_500_000L;
    public static final int MAX_GIF_FRAMES = 300;
    public static final long MAX_GIF_TOTAL_PIXELS = 240_000_000L;
    public static final int SAFETY_VERSION = 1;
    public static final int PUBLIC_VARIANT_VERSION = 2;
    public static final Set<Integer> PUBLIC_WIDTHS = Set.of(640, 768, 960, 1280, 1920);
    private static final float OPAQUE_PNG_JPEG_QUALITY = 0.90f;

    private static final Map<String, String> MEDIA_TYPES = Map.of(
        "png", "image/png",
        "jpeg", "image/jpeg",
        "jpg", "image/jpeg",
        "gif", "image/gif"
    );

    static {
        ImageIO.setUseCache(false);
    }

    public ValidatedBanner validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Выберите файл баннера.");
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_BANNER_BYTES) {
            throw new IllegalArgumentException("Размер баннера не должен превышать 5 МБ.");
        }

        byte[] source = file.getBytes();
        if (source.length == 0 || source.length > MAX_BANNER_BYTES) {
            throw new IllegalArgumentException("Размер баннера не должен превышать 5 МБ.");
        }

        String mediaType = inspect(source);
        return new ValidatedBanner(source, mediaType, SAFETY_VERSION);
    }


    public boolean isSupportedPublicWidth(int width) {
        return PUBLIC_WIDTHS.contains(width);
    }

    public byte[] renderPublicVariant(byte[] source, String mediaType, int maxWidth) throws IOException {
        return renderPublicVariantPayload(source, mediaType, maxWidth).data();
    }

    public RenderedBanner renderPublicVariantPayload(byte[] source, String mediaType, int maxWidth) throws IOException {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("Баннер отсутствует.");
        }
        if (!isSupportedPublicWidth(maxWidth)) {
            throw new IllegalArgumentException("Неподдерживаемая ширина варианта баннера.");
        }
        if ("image/gif".equals(mediaType)) {
            return new RenderedBanner(source, mediaType);
        }
        if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
            throw new IllegalArgumentException("Неподдерживаемый тип баннера.");
        }

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
        if (decoded == null) {
            throw new IOException("Сохранённый баннер не декодируется.");
        }
        if (decoded.getWidth() <= maxWidth) {
            return new RenderedBanner(source, mediaType);
        }

        double scale = (double) maxWidth / decoded.getWidth();
        int targetHeight = Math.max(1, (int) Math.round(decoded.getHeight() * scale));
        boolean transparentPng = "image/png".equals(mediaType) && decoded.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(
            maxWidth,
            targetHeight,
            transparentPng ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(decoded, 0, 0, maxWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        String format = "image/png".equals(mediaType) ? "png" : "jpg";
        byte[] resized;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(target, format, output)) {
                throw new IOException("Кодировщик баннера недоступен.");
            }
            resized = output.toByteArray();
        }

        byte[] losslessBest = resized.length < source.length ? resized : source;
        if ("image/png".equals(mediaType) && !transparentPng) {
            byte[] jpeg = writeJpeg(target, OPAQUE_PNG_JPEG_QUALITY);
            if (jpeg.length * 5L < losslessBest.length * 4L) {
                return new RenderedBanner(jpeg, "image/jpeg");
            }
        }

        return new RenderedBanner(losslessBest, mediaType);
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG-кодировщик баннера недоступен.");
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

    private String inspect(byte[] source) throws IOException {
        try (
            ByteArrayInputStream bytes = new ByteArrayInputStream(source);
            ImageInputStream input = ImageIO.createImageInputStream(bytes)
        ) {
            if (input == null) {
                throw new IllegalArgumentException("Файл не является поддерживаемым изображением.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Разрешены только PNG, JPEG и GIF изображения.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mediaType = MEDIA_TYPES.get(format);
                if (mediaType == null) {
                    throw new IllegalArgumentException("Разрешены только PNG, JPEG и GIF изображения.");
                }

                int frames = "gif".equals(format) ? reader.getNumImages(true) : 1;
                if (frames < 1 || frames > MAX_GIF_FRAMES) {
                    throw new IllegalArgumentException("GIF содержит слишком много кадров.");
                }

                long totalPixels = 0;
                for (int index = 0; index < frames; index++) {
                    int width = reader.getWidth(index);
                    int height = reader.getHeight(index);
                    long pixels = (long) width * (long) height;
                    if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT || pixels > MAX_FRAME_PIXELS) {
                        throw new IllegalArgumentException("Разрешение баннера не должно превышать 3000x1500 и 4,5 млн пикселей на кадр.");
                    }
                    totalPixels += pixels;
                    if (totalPixels > MAX_GIF_TOTAL_PIXELS) {
                        throw new IllegalArgumentException("Анимация баннера слишком сложная.");
                    }
                }
                return mediaType;
            } catch (IndexOutOfBoundsException | javax.imageio.IIOException exception) {
                throw new IllegalArgumentException("Изображение повреждено или имеет неподдерживаемую структуру.", exception);
            } finally {
                reader.dispose();
            }
        }
    }

    public record ValidatedBanner(byte[] data, String mediaType, int safetyVersion) {
    }

    public record RenderedBanner(byte[] data, String mediaType) {
    }
}
