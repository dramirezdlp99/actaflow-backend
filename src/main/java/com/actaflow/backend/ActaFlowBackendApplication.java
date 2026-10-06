package com.actaflow.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ActaFlowBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActaFlowBackendApplication.class, args);
    }
}
