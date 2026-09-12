package com.codeatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(com.codeatlas.config.CodeAtlasProperties.class)
public class CodeAtlasApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeAtlasApplication.class, args);
    }
}
