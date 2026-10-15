package com.actaflow.backend.expedientes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Microservicio de expedientes de ActaFlow. Solo habla gRPC (nativo y gRPC-Web). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LegalCaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegalCaseServiceApplication.class, args);
    }
}
