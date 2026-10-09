package com.psych.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psych.model.User;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化演示账号（仅当 Redis 里不存在时才写入，重复启动不会覆盖）。
 *   admin / 123456 → 管理员
 *   user  / 123456 → 普通用户
 */
@Component                        // 【Spring 组件注册】告诉 Spring Boot：启动时请创建这个类的实例并放入"管理池"（IoC 容器），之后别人要用直接找 Spring 拿，不用自己 new
public class DataInitializer implements CommandLineRunner {  // 【实现启动钩子】CommandLineRunner 是 Spring 提供的"启动后自动执行"接口。类声明了 implements，就等于签了合同：我承诺实现 run() 方法，Spring 容器完全启动后会自动调用它

    private final StringRedisTemplate redis;                 // 【声明 Redis 操作工具】final 表示"赋值后不能再换对象"。Spring 启动时会通过构造方法把已配好的 Redis 连接自动注入进来（这叫"依赖注入"）
    private final ObjectMapper objectMapper;                 // 【声明 JSON 转换工具】同样 final + 构造注入。它1的作用是把 Java 对象（如 User）转成 JSON 字符串，或者反向把 JSON 字符串转回 Java 对象
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();  // 【声明密码加密器】这里直接用 = new 自己创建，因为 BCryptPasswordEncoder 很轻量，不需要 Spring 管理。它会帮我们把明文"123456"变成带盐值的密文（每次加密结果都不同）

    public DataInitializer(StringRedisTemplate redis, ObjectMapper objectMapper) {  // 【构造方法】Spring 看到 @Component 就会来创建这个类的实例。创建时它会"自动侦查"：构造方法需要什么参数？然后从自己的管理池里找出对应的 Bean 填进来
        this.redis = redis;                                  // 【保存 Redis 工具】把 Spring 注入的 redis 参数存到成员变量，供下面的 seed() 方法使用。this 表示"当前对象"，用来区分参数名和成员变量名
        this.objectMapper = objectMapper;                    // 【保存 JSON 工具】同理，把注入的 objectMapper 保存下来，后面要把 User 对象转成 JSON 存 Redis
    }

    @Override                                                // 【重写标记】告诉编译器：下面这个方法是在实现接口（CommandLineRunner）里定义的方法。如果方法名或参数写错了，编译器会立刻报错提醒
    public void run(String... args) {                        // 【启动后自动执行】这是 CommandLineRunner 接口规定的唯一方法。Spring Boot 容器初始化完毕后，会自动调用一次。String... args 是可变长参数，用于接收命令行传入的参数（本项目没用到）
        seed("admin", "admin@example.com", "admin");         // 【播种管理员账号】调用私有方法 seed()，参数依次是：用户ID、邮箱、角色。"admin" 角色会登录后进入管理后台
        seed("user", "user@example.com", "user");            // 【播种普通用户】再创建一个普通用户账号，角色是 "user"，登录后进入用户前端页面
    }

    private void seed(String userId, String email, String role) {  // 【私有工具方法】private 表示"仅供本类内部使用"。这个方法负责"检查 Redis 有没有这个账号，没有就创建并写入"。三个参数：用户名、邮箱、角色
        String key = "user:" + userId;                       // 【拼接 Redis 键名】Redis 社区约定用冒号分隔层级，形成类似 "user:admin" 的键。这样以后按 "user:*" 前缀扫描就能找到所有用户
        if (Boolean.TRUE.equals(redis.hasKey(key))) {        // 【幂等检查：存在则跳过】redis.hasKey() 返回 Boolean（包装类，可能为 null）。用 Boolean.TRUE.equals() 做安全比较，避免 NullPointerException。如果账号已存在，直接 return 什么也不做——保证重复启动不会覆盖已有数据
            return;                                          // 【提前退出】这个 return 会立刻结束 seed() 方法，跳过后面的创建逻辑。这是一种"守卫语句"写法：先排除不需要处理的情况，代码读起来更直
        }
        User user = new User(                                // 【创建用户对象】调用 User.java 里定义的"全参数构造方法"，一次性给 5 个字段赋值
                userId,                                      //  第 1 个参数 → User.userId（用户名，同时也是 Redis 键的一部分）
                email,                                       //  第 2 个参数 → User.email（邮箱地址）
                encoder.encode("123456"),                    //  第 3 个参数 → User.password。这里用 BCrypt 把明文"123456"加密成密文。encode() 内部会自动生成随机"盐值"，所以每次结果都不同，但都能验证
                role,                                        //  第 4 个参数 → User.role（角色：admin 或 user，决定登录后看到哪个界面）
                System.currentTimeMillis()                   //  第 5 个参数 → User.createdAt。System.currentTimeMillis() 返回从 1970 年 1 月 1 日零点到现在的毫秒数（时间戳格式），全球统一，无时区歧义
        );                                                   // 构造方法调用结束，括号闭合。此时 user 变量里已经装好了一个完整的用户对象
        try {                                                // 【异常捕获开始】objectMapper.writeValueAsString() 方法声明了可能抛出 JsonProcessingException（比如对象里有循环引用转不了 JSON）。Java 是编译型语言，这种"已检查异常"必须处理：要么 try-catch，要么方法签名加 throws。这里选 try-catch
            redis.opsForValue().set(key, objectMapper.writeValueAsString(user));  // 【三步合一写入 Redis】① objectMapper.writeValueAsString(user) 把 User 对象序列化成 JSON 字符串；② redis.opsForValue() 拿到 Redis 的"字符串值"操作器；③ .set(key, json) 把键值对写入 Redis。最终 Redis 里存的是 {"userId":"admin", "email":"...", ...} 这样的 JSON
            System.out.println("已初始化演示账号: " + userId + " / 123456 (" + role + ")");  // 【控制台打印日志】告诉开发者这个演示账号已经成功写入。生产环境一般用 SLF4J/Logback 代替 System.out.println，但演示项目用 println 更简单直接
        } catch (JsonProcessingException e) {                // 【捕获 JSON 异常】万一 User 对象里有特殊字段导致 Jackson 无法序列化，就会跳到这里。e 变量就是异常对象，里面包含了错误原因和堆栈信息
            throw new IllegalStateException("初始化账号失败: " + userId, e);  // 【包装后重新抛出】把 JsonProcessingException 包装成 IllegalStateException（运行时异常）再抛出。带上人类可读的错误信息，这样启动日志里能看到"初始化账号失败: admin"，方便定位问题；同时保留原始异常 e 作为"cause"，不丢失底层原因
        }                                                    // try-catch 块结束
    }                                                        // seed() 方法结束
}                                                            // DataInitializer 类结束
