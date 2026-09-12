package com.codeatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CodeAtlasApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeAtlasApplication.class, args);
    }
}
