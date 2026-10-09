package com.psych.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.dto.LoginRequest;
import com.psych.dto.LoginResponse;
import com.psych.dto.RegisterRequest;
import com.psych.dto.UserInfo;
import com.psych.exception.BizException;
import com.psych.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 认证服务：用户数据和登录会话都存在 Redis 里。
 *
 * Redis 键设计（可以用 redis-cli 用 keys * 看到）：
 *   user:{用户ID}    → 用户信息 JSON（永久保存）
 *   token:{令牌}     → 用户ID（带过期时间，相当于登录有效期）
 */
@Service
public class AuthService {

    private static final String USER_PREFIX = "user:";
    private static final String TOKEN_PREFIX = "token:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // BCrypt：同一密码每次加密结果不同，比对时用 matches，不能用 equals
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.session.ttl-hours:2}")
    private long sessionTtlHours;

    public AuthService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ===== 1. 注册 =====
    public void register(RegisterRequest request) {
        String key = USER_PREFIX + request.userId();
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            throw new BizException("该用户ID已被注册");
        }

        User user = new User(
                request.userId(),
                request.email(),
                passwordEncoder.encode(request.password()),
                "user", // 自行注册的一律是普通用户
                System.currentTimeMillis()
        );
        redis.opsForValue().set(key, toJson(user));
    }

    // ===== 2. 登录：校验密码 → 生成令牌 → 令牌存入 Redis =====
    public LoginResponse login(LoginRequest request) {
        User user = findUser(request.userId());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            // 不区分“用户不存在”和“密码错”，避免被人枚举账号
            throw new BizException("用户ID或密码错误");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(
                TOKEN_PREFIX + token,
                user.getUserId(),
                Duration.ofHours(sessionTtlHours)
        );
        return new LoginResponse(token, user.getUserId(), user.getRole());
    }

    // ===== 3. 登出：删掉令牌即让会话失效 =====
    public void logout(String authHeader) {
        String token = parseToken(authHeader);
        if (token != null) {
            redis.delete(TOKEN_PREFIX + token);
        }
    }

    // ===== 4. 根据请求头里的令牌查当前用户（供 /me 和以后的受保护接口使用）=====
    public UserInfo currentUser(String authHeader) {
        String token = parseToken(authHeader);
        if (token == null) {
            throw new BizException("未登录");
        }

        String userId = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (userId == null) {
            // 键不存在 = 没登录过或已过期（TTL 到期 Redis 会自动删除）
            throw new BizException("登录已过期，请重新登录");
        }

        User user = findUser(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return new UserInfo(user.getUserId(), user.getEmail(), user.getRole());
    }

    // ===== 工具方法 =====

    /** 从 Redis 读用户并把 JSON 还原成对象，找不到返回 null */
    private User findUser(String userId) {
        String json = redis.opsForValue().get(USER_PREFIX + userId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, User.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Redis 中的用户数据损坏: " + userId, e);
        }
    }

    /** 从 Authorization: Bearer xxx 头里取出令牌 */
    private String parseToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private String toJson(User user) {
        try {
            return objectMapper.writeValueAsString(user);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("用户数据序列化失败", e);
        }
    }
}
