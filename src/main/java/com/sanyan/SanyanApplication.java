package com.sanyan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SanyanApplication {
    public static void main(String[] args) {
        SpringApplication.run(SanyanApplication.class, args);
    }
}
