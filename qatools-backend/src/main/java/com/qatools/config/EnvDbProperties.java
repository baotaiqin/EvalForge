package com.qatools.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 application.properties 加载所有环境数据库配置
 * frontendUrl -> EnvDbConfig 的映射
 */
@Component
public class EnvDbProperties {

    // Dev
    @Value("${env.db.dev.url}")
    private String devUrl;
    @Value("${env.db.dev.username}")
    private String devUsername;
    @Value("${env.db.dev.password}")
    private String devPassword;
    @Value("${env.db.dev.frontend-url}")
    private String devFrontendUrl;
    @Value("${env.db.dev.authorization:}")
    private String devAuthorization;
    @Value("${env.db.dev.api-username:}")
    private String devApiUsername;

    // Sit
    @Value("${env.db.sit.url}")
    private String sitUrl;
    @Value("${env.db.sit.username}")
    private String sitUsername;
    @Value("${env.db.sit.password}")
    private String sitPassword;
    @Value("${env.db.sit.frontend-url}")
    private String sitFrontendUrl;
    @Value("${env.db.sit.authorization:}")
    private String sitAuthorization;
    @Value("${env.db.sit.api-username:}")
    private String sitApiUsername;

    // Prod
    @Value("${env.db.prod.url}")
    private String prodUrl;
    @Value("${env.db.prod.username}")
    private String prodUsername;
    @Value("${env.db.prod.password}")
    private String prodPassword;
    @Value("${env.db.prod.frontend-url}")
    private String prodFrontendUrl;
    @Value("${env.db.prod.authorization:}")
    private String prodAuthorization;
    @Value("${env.db.prod.api-username:}")
    private String prodApiUsername;

    /**
     * frontendUrl -> EnvDbConfig
     */
    private final Map<String, EnvDbConfig> configMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        configMap.put(devFrontendUrl, new EnvDbConfig(devUrl, devUsername, devPassword, devFrontendUrl, devAuthorization, devApiUsername));
        configMap.put(sitFrontendUrl, new EnvDbConfig(sitUrl, sitUsername, sitPassword, sitFrontendUrl, sitAuthorization, sitApiUsername));
        configMap.put(prodFrontendUrl, new EnvDbConfig(prodUrl, prodUsername, prodPassword, prodFrontendUrl, prodAuthorization, prodApiUsername));
    }

    /**
     * 根据前端 URL 获取对应的数据库配置
     */
    public EnvDbConfig getByFrontendUrl(String frontendUrl) {
        return configMap.get(frontendUrl);
    }

    /**
     * 获取所有环境配置
     */
    public List<EnvDbConfig> getAll() {
        return new ArrayList<>(configMap.values());
    }
}
