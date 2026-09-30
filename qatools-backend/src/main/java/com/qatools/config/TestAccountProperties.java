package com.qatools.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评测专用账号配置
 * 根据前端URL获取对应环境的评测账号信息
 */
@Component
public class TestAccountProperties {

    @Value("${eval.test-account.dev.nickname:agent test}")
    private String devNickname;
    @Value("${eval.test-account.dev.password:}")
    private String devPassword;

    @Value("${eval.test-account.sit.nickname:agent test}")
    private String sitNickname;
    @Value("${eval.test-account.sit.password:}")
    private String sitPassword;

    @Value("${eval.test-account.prod.nickname:agent test}")
    private String prodNickname;
    @Value("${eval.test-account.prod.password:}")
    private String prodPassword;

    @Value("${env.db.dev.frontend-url}")
    private String devFrontendUrl;
    @Value("${env.db.sit.frontend-url}")
    private String sitFrontendUrl;
    @Value("${env.db.prod.frontend-url}")
    private String prodFrontendUrl;

    /** frontendUrl -> TestAccountConfig */
    private final Map<String, TestAccountConfig> configMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        configMap.put(devFrontendUrl, new TestAccountConfig(devNickname, devPassword));
        configMap.put(sitFrontendUrl, new TestAccountConfig(sitNickname, sitPassword));
        configMap.put(prodFrontendUrl, new TestAccountConfig(prodNickname, prodPassword));
    }

    public TestAccountConfig getByFrontendUrl(String frontendUrl) {
        return configMap.get(frontendUrl);
    }

    public static class TestAccountConfig {
        private final String nickname;
        private final String password;

        public TestAccountConfig(String nickname, String password) {
            this.nickname = nickname;
            this.password = password;
        }

        public String getNickname() { return nickname; }
        public String getPassword() { return password; }
    }
}
