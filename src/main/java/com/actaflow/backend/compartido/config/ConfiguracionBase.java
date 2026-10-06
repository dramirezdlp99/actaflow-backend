package com.actaflow.backend.compartido.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracionBase {

    /** Reloj inyectable: los tests de firmas y ventanas de tiempo usan uno fijo. */
    @Bean
    public Clock reloj() {
        return Clock.systemUTC();
    }
}
