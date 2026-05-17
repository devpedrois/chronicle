package com.chronicle.example.api;

import com.chronicle.example.ChronicleExampleApplication;
import com.chronicle.example.dto.AccountResponse;
import com.chronicle.example.dto.EventResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// [SECURITY] Full lifecycle E2E test — uses real HTTP server (RANDOM_PORT), not MockMvc
@SpringBootTest(
        classes = ChronicleExampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Testcontainers
@ActiveProfiles("test")
class AccountApiIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("chronicle_e2e_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE processed_projection_events");
        jdbcTemplate.execute("TRUNCATE TABLE projection_positions");
        jdbcTemplate.execute("ALTER TABLE balances DISABLE TRIGGER ALL");
        jdbcTemplate.execute("TRUNCATE TABLE balances");
        jdbcTemplate.execute("ALTER TABLE balances ENABLE TRIGGER ALL");
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");
        jdbcTemplate.execute("TRUNCATE TABLE events");
    }

    @Test
    @DisplayName("full lifecycle via REST API: create, deposit x2, withdraw, GET balance, GET events")
    void fullLifecycle_createDepositWithdraw_balanceAndEventsConsistent() throws InterruptedException {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        // Step 1: create account
        ResponseEntity<AccountResponse> createResp = restTemplate.exchange(
                "/api/accounts",
                HttpMethod.POST,
                new HttpEntity<>("{\"ownerName\":\"Integration Test\"}", json),
                AccountResponse.class
        );
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AccountResponse createdAccount = Objects.requireNonNull(createResp.getBody());
        UUID accountId = createdAccount.id();
        assertThat(accountId).isNotNull();
        assertThat(createdAccount.ownerName()).isEqualTo("Integration Test");

        // Step 2: first deposit
        ResponseEntity<AccountResponse> deposit1 = restTemplate.exchange(
                "/api/accounts/" + accountId + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>("{\"amountCents\":50000,\"description\":\"Initial deposit\"}", json),
                AccountResponse.class
        );
        assertThat(deposit1.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountResponse deposit1Body = Objects.requireNonNull(deposit1.getBody());
        assertThat(deposit1Body.balanceCents()).isEqualTo(50000L);

        // Step 3: second deposit
        ResponseEntity<AccountResponse> deposit2 = restTemplate.exchange(
                "/api/accounts/" + accountId + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>("{\"amountCents\":30000,\"description\":\"Second deposit\"}", json),
                AccountResponse.class
        );
        assertThat(deposit2.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountResponse deposit2Body = Objects.requireNonNull(deposit2.getBody());
        assertThat(deposit2Body.balanceCents()).isEqualTo(80000L);

        // Step 4: withdrawal
        ResponseEntity<AccountResponse> withdraw = restTemplate.exchange(
                "/api/accounts/" + accountId + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>("{\"amountCents\":20000,\"description\":\"ATM withdrawal\"}", json),
                AccountResponse.class
        );
        assertThat(withdraw.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountResponse withdrawBody = Objects.requireNonNull(withdraw.getBody());
        assertThat(withdrawBody.balanceCents()).isEqualTo(60000L);

        // Wait for async projection to converge (polls balances table)
        long deadline = System.currentTimeMillis() + 5_000;
        Long projectedBalance = null;
        while (System.currentTimeMillis() < deadline) {
            List<Long> rows = jdbcTemplate.queryForList(
                    "SELECT balance FROM balances WHERE account_id = ?", Long.class, accountId);
            if (!rows.isEmpty() && rows.get(0) == 60000L) {
                projectedBalance = rows.get(0);
                break;
            }
            Thread.sleep(200);
        }
        assertThat(projectedBalance)
                .as("Projection must converge to 60000 cents before timeout")
                .isEqualTo(60000L);

        // Step 5: GET account from projection
        ResponseEntity<AccountResponse> getResp = restTemplate.getForEntity(
                "/api/accounts/" + accountId, AccountResponse.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountResponse getBody = Objects.requireNonNull(getResp.getBody());
        assertThat(getBody.balanceCents()).isEqualTo(60000L);
        assertThat(getBody.ownerName()).isEqualTo("Integration Test");

        // Step 6: GET events — 4 events: create, deposit, deposit, withdraw
        ResponseEntity<List<EventResponse>> eventsResp = restTemplate.exchange(
                "/api/accounts/" + accountId + "/events",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(eventsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<EventResponse> events = Objects.requireNonNull(eventsResp.getBody());
        assertThat(events).hasSize(4);
        assertThat(events.get(0).eventType()).isEqualTo("AccountCreated");
        assertThat(events.get(1).eventType()).isEqualTo("MoneyDeposited");
        assertThat(events.get(2).eventType()).isEqualTo("MoneyDeposited");
        assertThat(events.get(3).eventType()).isEqualTo("MoneyWithdrawn");

        // Step 7: Verify consistency — balance from projection matches event stream logic
        long computedBalance = events.stream().mapToLong(e -> {
            Object amount = e.summary().get("amountCents");
            long cents = amount instanceof Number n ? n.longValue() : 0L;
            return switch (e.eventType()) {
                case "MoneyDeposited" -> cents;
                case "MoneyWithdrawn", "MoneyTransferred" -> -cents;
                default -> 0L;
            };
        }).sum();
        assertThat(computedBalance)
                .as("Balance computed from events must match projection balance")
                .isEqualTo(60000L);
    }
}
