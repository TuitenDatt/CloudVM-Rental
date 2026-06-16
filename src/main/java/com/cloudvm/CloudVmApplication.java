package com.cloudvm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point của ứng dụng Cloud VM Rental.
 *
 * @EnableAsync    — Kích hoạt xử lý bất đồng bộ (@Async) cho luồng khởi tạo EC2.
 * @EnableScheduling — Kích hoạt cron job (@Scheduled) cho luồng thu hồi instance.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class CloudVmApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudVmApplication.class, args);
    }
}
