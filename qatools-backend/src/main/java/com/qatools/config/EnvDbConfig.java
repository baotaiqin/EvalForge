package com.qatools.config;

import java.util.Map;

/**
 * 环境数据库配置项
 */
public class EnvDbConfig {
    private String url;
    private String username;
    private String password;
    private String frontendUrl;
    /** SemiMind 平台的 Authorization 请求头值 */
    private String authorization;
    /** SemiMind 平台的 API 用户名 */
    private String apiUsername;

    public EnvDbConfig() {}

    public EnvDbConfig(String url, String username, String password, String frontendUrl,
                       String authorization, String apiUsername) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.frontendUrl = frontendUrl;
        this.authorization = authorization;
        this.apiUsername = apiUsername;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFrontendUrl() { return frontendUrl; }
    public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }

    public String getAuthorization() { return authorization; }
    public void setAuthorization(String authorization) { this.authorization = authorization; }

    public String getApiUsername() { return apiUsername; }
    public void setApiUsername(String apiUsername) { this.apiUsername = apiUsername; }
}
