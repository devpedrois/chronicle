package com.chronicle.example.api;

import com.chronicle.example.ChronicleExampleApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

// Singleton pattern: container started once and shared across all subclasses (SecurityRegressionTest,
// AccountApiTest). Without this, Testcontainers stops the container after each class, but the
// cached Spring context still points to the old port → connection refused for the next class.
@SpringBootTest(classes = ChronicleExampleApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = createContainer();
        POSTGRES.start();
    }

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> createContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("chronicle_api_test")
                .withUsername("test")
                .withPassword("test");
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
