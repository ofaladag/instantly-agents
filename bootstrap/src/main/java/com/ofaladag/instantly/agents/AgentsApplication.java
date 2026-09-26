package com.ofaladag.instantly.agents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentsApplication.class, args);
    }
}
