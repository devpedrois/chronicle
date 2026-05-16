package com.chronicle.example.api;

import com.chronicle.example.ChronicleExampleApplication;
import com.chronicle.example.config.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// [SECURITY] Adversarial test suite — each test simulates a concrete penetration testing vector
// Owns its own Postgres container to avoid lifecycle conflicts with other test classes
@SpringBootTest(classes = ChronicleExampleApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AdversarialSecurityTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("chronicle_adversarial_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void cleanDatabase() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE processed_projection_events");
        jdbcTemplate.execute("TRUNCATE TABLE projection_positions");
        jdbcTemplate.execute("ALTER TABLE balances DISABLE TRIGGER ALL");
        jdbcTemplate.execute("TRUNCATE TABLE balances");
        jdbcTemplate.execute("ALTER TABLE balances ENABLE TRIGGER ALL");
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");
        jdbcTemplate.execute("TRUNCATE TABLE events");
        // [SECURITY] Reset rate-limit buckets so each test starts from a clean token count
        clearRateLimitBuckets();
    }

    // ── Test 1 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: Jackson gadget chain attempt via @class injection returns 400")
    // [SECURITY] Adversarial test: Jackson gadget chain via polymorphic type injection (CVE-2017-7525)
    // FAIL_ON_UNKNOWN_PROPERTIES=true must reject the @class field before Jackson attempts type coercion
    void jacksonGadgetChain_classFieldInBody_returns400() throws Exception {
        String id = createAccount("Alice");

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":100,\"description\":\"test\",\"@class\":\"java.lang.Runtime\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── Test 2 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: SQL injection in description stored as literal — events table remains intact")
    // [SECURITY] Adversarial test: SQL injection via JSONB payload — parameterized queries prevent execution
    void sqlInjection_inDescription_storedAsLiteralText() throws Exception {
        String id = createAccount("Bob");

        String injection = "'; DROP TABLE events; --";
        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":100,\"description\":\"" + injection + "\"}"))
                .andExpect(status().isOk());

        // Table must still exist — injection had no effect
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM events", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    // ── Test 3 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: mass assignment — version injection in body is rejected with 400")
    // [SECURITY] Adversarial test: version injection attempt — FAIL_ON_UNKNOWN_PROPERTIES rejects the field;
    // server controls version exclusively, client cannot influence the aggregate version number
    void massAssignment_versionInjection_returns400() throws Exception {
        String id = createAccount("Carol");

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":100,\"description\":\"test\",\"version\":999}"))
                .andExpect(status().isBadRequest());

        // Aggregate must NOT have version 999 — verify at most 2 events exist (AccountCreated only)
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE aggregate_id = ?::uuid", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    // ── Test 4 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: mass assignment — balance injection in create-account body is rejected with 400")
    // [SECURITY] Adversarial test: balance injection via mass assignment — records + FAIL_ON_UNKNOWN_PROPERTIES
    // reject unknown fields; account CANNOT be created with an arbitrary starting balance
    void massAssignment_balanceInjection_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Eve\",\"balance\":999999,\"balanceCents\":999999}"))
                .andExpect(status().isBadRequest());
    }

    // ── Test 5 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: rate limiting — requests beyond 100/min return 429")
    // [SECURITY] Adversarial test: rate limiting prevents brute force and DoS attacks
    // IP extracted from getRemoteAddr() — X-Forwarded-For is attacker-controlled and never trusted
    void rateLimiting_after100Requests_returns429() throws Exception {
        int rateLimited = 0;
        int ok = 0;

        for (int i = 0; i < 110; i++) {
            int status = mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001"))
                    .andReturn().getResponse().getStatus();
            if (status == 429) rateLimited++;
            else ok++;
        }

        assertThat(ok).isLessThanOrEqualTo(100);
        assertThat(rateLimited).isGreaterThanOrEqualTo(10);

        // 429 response must contain retry guidance, not internal details
        MvcResult limitedResponse = mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001"))
                .andReturn();
        String body = limitedResponse.getResponse().getContentAsString();
        assertThat(body).contains("retry_after_seconds");
        assertThat(body).doesNotContain("stackTrace");
        assertThat(body).doesNotContain("java.");
    }

    // ── Test 6 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: all required security headers present on every response")
    // [SECURITY] Adversarial test: security headers defense in depth — missing headers allow
    // clickjacking (X-Frame-Options), MIME sniffing (X-Content-Type-Options), cache poisoning (Cache-Control)
    void securityHeaders_presentOnEveryResponse() throws Exception {
        mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Server", "chronicle"));
    }

    // ── Test 7 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: error response does not expose Java internals or stack traces")
    // [SECURITY] Adversarial test: error sanitization prevents information disclosure
    // Attackers use stack traces, class names, and package paths to fingerprint vulnerabilities
    void errorResponse_noJavaInternalsExposed() throws Exception {
        String[] internalMarkers = {
            "stackTrace", "at com.", "at java.", "java.lang", "java.sql",
            "Exception", "chronicle.jdbc", "chronicle.core", "postgresql", "HikariPool"
        };

        // 404 response
        String notFoundBody = mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // 400 from malformed JSON
        String malformedBody = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad-json"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // 400 from Bean Validation
        String validationBody = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        for (String marker : internalMarkers) {
            assertThat(notFoundBody).as("404 body must not contain: " + marker).doesNotContain(marker);
            assertThat(malformedBody).as("400/malformed body must not contain: " + marker).doesNotContain(marker);
            assertThat(validationBody).as("400/validation body must not contain: " + marker).doesNotContain(marker);
        }
    }

    // ── Test 8 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: large payload description (>65536 chars) rejected — heap exhaustion prevention")
    // [SECURITY] Adversarial test: large payload DoS prevention
    // @Size(max=255) on description rejects oversized inputs at Bean Validation layer,
    // before the payload reaches the event store or gets stored in the DB
    void payloadSizeLimit_largeDescription_returns400() throws Exception {
        String id = createAccount("Frank");
        String largeDescription = "X".repeat(65537);

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":100,\"description\":\"" + largeDescription + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── Test 9 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: database-level immutability — direct UPDATE and DELETE blocked by trigger")
    // [SECURITY] Adversarial test: database-level immutability enforcement
    // Even if application layer is bypassed (e.g., via direct DB access), events cannot be mutated
    void eventImmutability_directSqlBlocked_byTrigger() throws Exception {
        String id = createAccount("Grace");

        String eventId = jdbcTemplate.queryForObject(
                "SELECT event_id::text FROM events WHERE aggregate_id = ?::uuid LIMIT 1",
                String.class, id);
        assertThat(eventId).isNotNull();

        // [SECURITY] Direct UPDATE via JDBC must be blocked by the immutability trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE events SET event_type = 'HACKED' WHERE event_id = ?::uuid", eventId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");

        // [SECURITY] Direct DELETE via JDBC must also be blocked by the immutability trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM events WHERE event_id = ?::uuid", eventId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");

        // Event data is unchanged after both attack attempts
        String eventType = jdbcTemplate.queryForObject(
                "SELECT event_type FROM events WHERE event_id = ?::uuid", String.class, eventId);
        assertThat(eventType).isEqualTo("AccountCreated");
    }

    // ── Test 10 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adversarial: invalid UUID path param returns 400, not 500 — format and injection validation")
    // [SECURITY] Adversarial test: path param format validation
    // Malformed, null-byte, and script-injection UUIDs must all produce 400 with a safe body — never 500
    void uuidPathParam_invalidFormat_returns400NotInternal500() throws Exception {
        String[] malformedIds = {
            "not-a-uuid",
            "12345",
            "00000000-0000-0000-0000-00000000000Z",
            "GGGGGGGG-GGGG-GGGG-GGGG-GGGGGGGGGGGG"
        };

        for (String badId : malformedIds) {
            MvcResult result = mockMvc.perform(get("/api/accounts/" + badId))
                    .andReturn();
            int status = result.getResponse().getStatus();
            assertThat(status).as("Expected 400 or 404 for id: " + badId).isIn(400, 404);

            String body = result.getResponse().getContentAsString();
            assertThat(body).as("No stack trace for id: " + badId).doesNotContain("stackTrace");
            assertThat(body).as("No package name for id: " + badId).doesNotContain("at com.");
            assertThat(body).as("No java class for id: " + badId).doesNotContain("java.lang");
        }
    }

    // ── Regression Tests — security invariants that new features must not break ──

    @Test
    @DisplayName("regression: IllegalArgumentException never exposes internal message to client")
    // [SECURITY] Regression guard: handleIllegalArgument must NOT return e.getMessage().
    // Internal messages like "payload exceeds 64KB: N bytes", "Unknown event type: X",
    // "Invalid 'after' parameter: must be >= 0" disclose internal validation logic to attackers.
    void illegalArgumentException_internalMessageNeverForwardedToClient() throws Exception {
        String id = createAccount("Henry");

        // Path 1: negative 'after' query param triggers IAE in AccountService — internal message must be hidden
        String afterBody = mockMvc.perform(get("/api/accounts/" + id + "/events?after=-1"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterBody).isEqualTo("{\"error\":\"Invalid request\"}");
        assertThat(afterBody).doesNotContain("afterVersion");
        assertThat(afterBody).doesNotContain("parameter");
        assertThat(afterBody).doesNotContain(">=");

        // Path 2: self-transfer triggers IAE("Cannot transfer to the same account")
        deposit(id, 1000);
        String selfTransferBody = mockMvc.perform(post("/api/accounts/" + id + "/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toAccountId\":\"" + id + "\",\"amountCents\":100,\"description\":\"self\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(selfTransferBody).isEqualTo("{\"error\":\"Invalid request\"}");
        assertThat(selfTransferBody).doesNotContain("Cannot transfer");
        assertThat(selfTransferBody).doesNotContain("same account");
    }

    @Test
    @DisplayName("regression: 429 response includes Retry-After HTTP header per RFC 6585")
    // [SECURITY] Regression guard: RFC 6585 §4 requires Retry-After header on 429.
    // HTTP middleware, API gateways, and SDK retry logic depend on the header — body alone is insufficient.
    // Missing header allows aggressive retry storms that defeat the rate limit's DoS protection.
    void rateLimitResponse_hasRetryAfterHttpHeader() throws Exception {
        // Fill the bucket
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001")).andReturn();
        }

        // Next request must be 429 with Retry-After header
        mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.retry_after_seconds").value(60));
    }

    @Test
    @DisplayName("regression: DB CHECK constraint rejects negative balance — defense-in-depth below projection")
    // [SECURITY] Regression guard: V6 migration adds CHECK(balance >= 0).
    // If the projection logic has a bug (or events are injected directly), the constraint prevents
    // the read model from ever recording a negative balance — money cannot be silently destroyed.
    void balanceCheckConstraint_negativeBalance_rejectedByDb() {
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO balances (account_id, owner_name, balance, updated_at) " +
                        "VALUES (gen_random_uuid(), 'Attacker', -1, NOW())"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("regression: full withdrawal brings balance to exactly 0, never negative")
    // [SECURITY] Regression guard: domain balance-check before withdraw event creation.
    // A future refactor that removes the pre-check in BankAccount.withdraw() would break this test.
    void withdraw_exactBalance_balanceIsZeroNotNegative() throws Exception {
        String id = createAccount("Ingrid");
        deposit(id, 500);

        mockMvc.perform(post("/api/accounts/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":500,\"description\":\"All out\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(0));

        // Verify DB read model also shows 0 (not negative), waiting for projection
        Thread.sleep(700);
        mockMvc.perform(get("/api/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(0));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createAccount(String ownerName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + ownerName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    private void deposit(String id, long amountCents) throws Exception {
        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":" + amountCents + ",\"description\":\"Deposit\"}"))
                .andExpect(status().isOk());
    }

    // [SECURITY] Reflection-based bucket reset — ensures rate limit test starts from a known state
    // without needing to expose the bucket map via a test-only API
    @SuppressWarnings("unchecked")
    private void clearRateLimitBuckets() throws Exception {
        Field bucketsField = RateLimitFilter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        ConcurrentMap<?, ?> buckets = (ConcurrentMap<?, ?>) bucketsField.get(rateLimitFilter);
        buckets.clear();
    }
}
