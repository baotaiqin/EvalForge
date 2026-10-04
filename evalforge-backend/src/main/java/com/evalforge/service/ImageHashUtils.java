package com.evalforge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 图片感知哈希（aHash）工具，供 PptScoringService / HtmlScoringService 共用。
 * 用于在数据集答案与模型输出图片之间进行视觉相似度比较。
 */
public final class ImageHashUtils {

    private static final Logger log = LoggerFactory.getLogger(ImageHashUtils.class);

    private static final int HASH_GRID_SIZE = 8;

    private ImageHashUtils() {
    }

    /**
     * 将 expectedImages 中的每张图片，在 generatedImages 中找到最相似的一张（贪心匹配，不要求一一对应）。
     * 返回所有匹配的平均相似度（0-1）。expectedImages 为空时视为完全匹配。
     */
    public static double compareImages(List<byte[]> generatedImages, List<byte[]> expectedImages) {
        return compareImageSets(expectedImages, generatedImages);
    }

    public static double compareImageSets(List<byte[]> expectedImages, List<byte[]> generatedImages) {
        if (expectedImages == null || expectedImages.isEmpty()) {
            return 1.0;
        }

        List<Long> expectedHashes = computeHashes(expectedImages);
        List<Long> generatedHashes = computeHashes(generatedImages);

        if (expectedHashes.isEmpty()) {
            return 1.0;
        }

        if (generatedHashes.isEmpty()) {
            return 0.0;
        }

        double totalSimilarity = 0.0;
        for (long expectedHash : expectedHashes) {
            double bestSimilarity = 0.0;
            for (long generatedHash : generatedHashes) {
                bestSimilarity = Math.max(bestSimilarity, hashSimilarity(expectedHash, generatedHash));
            }
            totalSimilarity += bestSimilarity;
        }
        return totalSimilarity / expectedHashes.size();
    }

    private static List<Long> computeHashes(List<byte[]> images) {
        List<Long> hashes = new ArrayList<>();
        if (images == null) {
            return hashes;
        }
        for (byte[] imageBytes : images) {
            Long hash = computeAverageHash(imageBytes);
            if (hash != null) {
                hashes.add(hash);
            }
        }
        return hashes;
    }

    /**
     * 将图片缩采样为 8x8 灰度网格，并以平均灰度生成 64 位 aHash。
     * 直接在原图采样，避免额外创建缩放后的图片对象。
     */
    private static Long computeAverageHash(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }

            double[] grid = new double[HASH_GRID_SIZE * HASH_GRID_SIZE];
            double sum = 0.0;
            for (int y = 0; y < HASH_GRID_SIZE; y++) {
                for (int x = 0; x < HASH_GRID_SIZE; x++) {
                    int srcX = Math.min((int) ((x + 0.5) * width / HASH_GRID_SIZE), width - 1);
                    int srcY = Math.min((int) ((y + 0.5) * height / HASH_GRID_SIZE), height - 1);
                    double gray = toGrayscale(image.getRGB(srcX, srcY));
                    grid[y * HASH_GRID_SIZE + x] = gray;
                    sum += gray;
                }
            }

            double average = sum / grid.length;
            long hash = 0L;
            for (int i = 0; i < grid.length; i++) {
                if (grid[i] >= average) {
                    hash |= (1L << i);
                }
            }
            return hash;
        } catch (Exception e) {
            log.debug("图片解析失败，跳过该图片: {}", e.getMessage());
            return null;
        }
    }

    private static double toGrayscale(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    private static double hashSimilarity(long hashA, long hashB) {
        int hammingDistance = Long.bitCount(hashA ^ hashB);
        return 1.0 - (hammingDistance / 64.0);
    }
}
