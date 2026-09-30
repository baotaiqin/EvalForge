package com.qatools.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据环境 URL 识别所属平台（SemiClaw / SemiMind）。
 * 各平台的已知 host:port 通过配置文件维护，避免在业务代码中硬编码内网地址。
 */
@Component
public class PlatformDetectionProperties {

    @Value("${qatools.platform-detection.semiclaw-hosts:demo-claw-sit.example.invalid:7630,demo-claw-prod.example.invalid:8630}")
    private String semiclawHosts;

    @Value("${qatools.platform-detection.semimind-hosts:demo-dev.example.invalid:6111,demo-sit.example.invalid:7111,demo-prod.example.invalid:8111}")
    private String semimindHosts;

    public boolean isSemiclawUrl(String url) {
        return matchesAny(url, semiclawHosts);
    }

    public boolean isSemimindUrl(String url) {
        return matchesAny(url, semimindHosts);
    }

    private boolean matchesAny(String url, String hostsCsv) {
        if (url == null || url.isBlank() || hostsCsv == null || hostsCsv.isBlank()) {
            return false;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        for (String host : hostsCsv.split(",")) {
            String trimmed = host.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && lowerUrl.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
