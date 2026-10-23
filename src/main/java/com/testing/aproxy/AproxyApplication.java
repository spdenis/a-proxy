package com.testing.aproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AproxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AproxyApplication.class, args);
    }
}
