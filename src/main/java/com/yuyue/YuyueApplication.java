package com.yuyue;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@EnableScheduling
@SpringBootApplication
public class YuyueApplication {

    public static void main(String[] args) {
        // 全站统一上海时间：固定 JVM 默认时区，LocalDateTime.now() / LocalDate.now() /
        // 定时任务 / 日志时间戳都按 Asia/Shanghai 走，不随服务器所在时区变化。
        // DB 会话时区由 application.yml 的 hikari.connection-init-sql（SET time_zone='+8:00'）配套固定，
        // 两层配合后 SQL 里的 CURRENT_TIMESTAMP 与 Java 写入的时间完全一致。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(YuyueApplication.class, args);
    }
}
