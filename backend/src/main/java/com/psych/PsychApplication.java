package com.psych;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 心理咨询平台后端启动类。
 * 运行方式：mvn spring-boot:run，启动后监听 8080 端口。
 */
@SpringBootApplication
public class PsychApplication {

    public static void main(String[] args) {
        SpringApplication.run(PsychApplication.class, args);
    }
}
