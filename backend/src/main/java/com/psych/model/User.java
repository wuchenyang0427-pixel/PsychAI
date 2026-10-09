package com.psych.model;

/**
 * 用户实体。以 JSON 形式存放在 Redis 的 user:{用户ID} 键中。
 */
public class User {

    /** 用户ID（手机号），同时作为 Redis 键的一部分，唯一 */
    private String userId;

    /** 邮箱 */
    private String email;

    /** 密码密文（BCrypt 加密后的字符串，不存明文） */
    private String password;

    /** 角色：admin / user，决定登录后进入后台还是用户前端 */
    private String role;

    /** 创建时间（毫秒时间戳） */
    private Long createdAt;

    public User() {
    }

    public User(String userId, String email, String password, String role, Long createdAt) {
        this.userId = userId;
        this.email = email;
        this.password = password;
        this.role = role;
        this.createdAt = createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
