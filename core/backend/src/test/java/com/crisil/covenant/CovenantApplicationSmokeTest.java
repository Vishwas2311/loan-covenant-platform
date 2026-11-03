package com.crisil.covenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CovenantApplicationSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(CovenantApplication.class)).isNotNull();
    }

    @Test
    void mainStarts() {
        CovenantApplication.main(new String[] {
                "--spring.profiles.active=test",
                "--spring.main.web-application-type=none",
                "--server.port=0",
                "--spring.main.lazy-initialization=true",
                "--spring.task.scheduling.shutdown.await-termination=false"});
    }
}
