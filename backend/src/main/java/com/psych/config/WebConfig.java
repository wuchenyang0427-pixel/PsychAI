package com.psych.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * 说明：前端的 /api 请求在开发期走 Vite 代理、部署后走 Nginx 反向代理，两种情况下都是同源，
 * 本来不需要 CORS。但浏览器对同源 POST 同样会带上 Origin 头，而一旦注册了 CORS 映射，
 * Spring 就会拿这个 Origin 去校验——不在白名单里的主机名会直接被拒（403 Invalid CORS request）。
 *
 * 之前只放行了 http://localhost:5173，导致换用 127.0.0.1、局域网 IP 或域名访问时，
 * 登录、对话等所有 POST 接口全部 403，页面表现为"点登录没反应"。
 * 这里改为按模式放行，不绑定具体主机名；因为鉴权走 Authorization 头而非 Cookie，
 * 不存在凭证随请求自动携带的问题。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
