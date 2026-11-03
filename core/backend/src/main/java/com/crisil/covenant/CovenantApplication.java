package com.crisil.covenant;

import com.crisil.covenant.config.IngestionProperties;
import com.crisil.covenant.config.SupportProperties;
import com.crisil.covenant.security.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
@EnableConfigurationProperties({IngestionProperties.class, SupportProperties.class, SecurityProperties.class})
public class CovenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(CovenantApplication.class, args);
    }
}
