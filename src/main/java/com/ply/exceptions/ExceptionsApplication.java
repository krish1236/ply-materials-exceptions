package com.ply.exceptions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExceptionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExceptionsApplication.class, args);
    }
}
