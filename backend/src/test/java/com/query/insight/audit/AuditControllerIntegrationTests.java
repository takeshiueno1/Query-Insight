package com.query.insight.audit;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class AuditControllerIntegrationTests {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void listAcceptsMissingAndBlankActionFilters() {
        AuditController controller = new AuditController(jdbc);

        assertThatCode(() -> controller.list(null)).doesNotThrowAnyException();
        assertThatCode(() -> controller.list(" ")).doesNotThrowAnyException();
    }
}
