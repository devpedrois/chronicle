package com.chronicle.example;

import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@SpringBootApplication
public class TestChronicleEngineApp {

    public static void main(String[] args) {
        SpringApplication.run(TestChronicleEngineApp.class, args);
    }

    @Bean
    public JdbcEventStore jdbcEventStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcEventStore(jdbcTemplate, transactionManager);
    }

    @Bean
    public JdbcSnapshotStore jdbcSnapshotStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcSnapshotStore(jdbcTemplate, transactionManager);
    }

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }
}
