package com.chronicle.example.api;

import com.chronicle.core.projection.ProjectionEngine;
import com.chronicle.example.config.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression guard suite — each test encodes a concrete attack vector so that
 * future feature additions that accidentally re-open a closed vulnerability
 * will be caught immediately by CI.
 *
 * <p>Tests are independent of each other.  New features MUST NOT remove or
 * relax any assertion here without a corresponding security-review commit.
 */
// [SECURITY] Regression test suite — encodes closed vulnerabilities as permanent CI gates
class SecurityRegressionTest extends AbstractApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private ProjectionEngine projectionEngine;

    @BeforeEach
    void cleanDatabase() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE processed_projection_events");
        jdbcTemplate.execute("TRUNCATE TABLE projection_positions");
        jdbcTemplate.execute("ALTER TABLE balances DISABLE TRIGGER ALL");
        jdbcTemplate.execute("TRUNCATE TABLE balances");
        jdbcTemplate.execute("ALTER TABLE balances ENABLE TRIGGER ALL");
        jdbcTemplate.execute("TRUNCATE TABLE snapshots");
        jdbcTemplate.execute("TRUNCATE TABLE events");
        // [SECURITY] Reset rate-limit buckets so each test starts clean.
        // The bucket map persists across test methods in the same Spring context.
        // Without clearing, rateLimiting_xForwardedForIgnored() would exhaust all tokens
        // and poison subsequent tests that expect 2xx responses.
        clearRateLimitBuckets();
        // Reset projection engine backoff — TRUNCATE of events/projection_positions can trigger
        // "cursor not found" errors in loadAllAfter(), causing exponential backoff accumulation
        // across tests that poisons subsequent test classes sharing the same Spring context.
        projectionEngine.clearBackoffState();
    }

    private void clearRateLimitBuckets() throws Exception {
        Field bucketsField = RateLimitFilter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        ConcurrentMap<?, ?> buckets = (ConcurrentMap<?, ?>) bucketsField.get(rateLimitFilter);
        buckets.clear();
    }

    // ── Control character injection ─────────────────────────────────────────────

    @Test
    @DisplayName("sec: ownerName with null byte (\\u0000) → 400, not stored")
    // [SECURITY] Null bytes in stored strings can terminate C-strings in downstream consumers,
    // corrupt log lines, and bypass prefix-based access control checks in some systems.
    void ownerName_nullByte_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"Test\\u0000Admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: ownerName with ASCII control char (\\u0001) → 400")
    // [SECURITY] ASCII control characters cause log injection and break downstream text processing.
    void ownerName_asciiControlChar_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"Test\\u0001Name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: ownerName with newline (\\n) → 400 — prevents log injection")
    // [SECURITY] Newline injection in ownerName allows an attacker to forge log entries.
    // e.g. "Alice\nINFO: successful admin login" produces two separate log lines.
    void ownerName_newline_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"Alice\\nINFO: fake log\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: ownerName with carriage-return (\\r) → 400 — HTTP response splitting guard")
    // [SECURITY] CR in a string that reaches an HTTP header value enables response splitting.
    // ownerName never enters a header, but the guard prevents log injection and future misuse.
    void ownerName_carriageReturn_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"Evil\\rName\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: ownerName with C1 control char (\\u0085) → 400")
    // [SECURITY] C1 control characters (U+0080–U+009F) are invisible and cause display anomalies.
    // U+0085 (NEXT LINE) is a line-break in some parsers, enabling log injection on non-ASCII paths.
    void ownerName_c1ControlChar_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"TestName\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: deposit description with null byte → 400, not stored in event payload")
    // [SECURITY] Null byte in JSONB description could terminate string comparisons in consumers
    // and bypass description-based audit filters.
    void description_nullByte_returns400() throws Exception {
        String id = createAccount("Alice");
        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"amountCents\":100,\"description\":\"legit\\u0000hidden\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: withdraw description with newline → 400 — prevents log injection")
    void description_newline_returns400() throws Exception {
        String id = createAccount("Bob");
        deposit(id, 500);
        mockMvc.perform(post("/api/accounts/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"amountCents\":100,\"description\":\"normal\\nINFO: fake\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: transfer description with ASCII control char → 400")
    void transferDescription_controlChar_returns400() throws Exception {
        String from = createAccount("Carol");
        String to = createAccount("Dave");
        deposit(from, 1000);
        mockMvc.perform(post("/api/accounts/" + from + "/transfer")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"toAccountId\":\"" + to + "\",\"amountCents\":100,\"description\":\"hack\\u0007\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("sec: ownerName with valid Unicode (accented, CJK) → 201 accepted")
    // Regression guard: the @Pattern must not be over-eager and reject legitimate international names.
    void ownerName_validUnicode_returns201() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"José María Aznar\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"王小明\"}"))
                .andExpect(status().isCreated());
    }

    // ── GET /events — bounded response guard ───────────────────────────────────

    @Test
    @DisplayName("sec: GET /events exceeding max-events-per-response → 400 (heap exhaustion guard)")
    // [SECURITY] Unbounded event list response enables heap exhaustion: an attacker with multiple IPs
    // accumulates thousands of events then calls GET /events to force large heap allocation per request.
    // chronicle.api.max-events-per-response=5 is set in application-test.yml to make this testable.
    void getEvents_exceedingMaxEventsPerResponse_returns400() throws Exception {
        String id = createAccount("Eve");
        // Create 6 events (1 create + 5 deposits) — exceeds the test limit of 5
        for (int i = 0; i < 5; i++) {
            deposit(id, 100);
        }

        mockMvc.perform(get("/api/accounts/" + id + "/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    @DisplayName("sec: GET /events within limit → 200 — guard does not affect normal usage")
    void getEvents_withinLimit_returns200() throws Exception {
        String id = createAccount("Frank");
        deposit(id, 100);
        deposit(id, 200);

        // 3 events (create + 2 deposits) — below the test limit of 5
        mockMvc.perform(get("/api/accounts/" + id + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("sec: GET /events?after=N within limit → 200 — pagination works under guard")
    void getEvents_withAfterParam_withinLimit_returns200() throws Exception {
        String id = createAccount("Grace");
        for (int i = 0; i < 4; i++) {
            deposit(id, 10);
        }
        // 5 total events; after=3 returns 2 events — within limit
        mockMvc.perform(get("/api/accounts/" + id + "/events?after=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── Rate limiting — X-Forwarded-For bypass prevention ──────────────────────

    @Test
    @DisplayName("sec: X-Forwarded-For header ignored for rate limiting — only getRemoteAddr() counts")
    // [SECURITY] X-Forwarded-For is attacker-controlled when no trusted proxy exists.
    // Using it for rate limiting allows bypass: rotate fake IPs in the header to get unlimited requests.
    // RateLimitFilter MUST use HttpServletRequest.getRemoteAddr() exclusively.
    void rateLimiting_xForwardedForIgnored() throws Exception {
        // Exhaust all 100 tokens for the MockMvc test IP (127.0.0.1)
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001")).andReturn();
        }

        // Setting X-Forwarded-For to a different IP should NOT bypass rate limiting.
        // If the filter naively used X-Forwarded-For, this would succeed (200/404).
        // It must still be rate-limited (429).
        mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .header("X-Real-IP", "10.0.0.1"))
                .andExpect(status().isTooManyRequests());
    }

    // ── Content-Type handling ───────────────────────────────────────────────────

    @Test
    @DisplayName("sec: POST without Content-Type header → 415 or 400, never 500 with internals")
    // [SECURITY] Missing Content-Type must not trigger a 500 that exposes class names or stack traces.
    // A 415 Unsupported Media Type is the semantically correct response.
    void post_withoutContentType_neverReturns500() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .content("{\"ownerName\":\"Test\"}"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("Missing Content-Type must return 400 or 415, never 500")
                .isIn(400, 415);

        String body = result.getResponse().getContentAsString();
        assertThat(body).as("No stack trace").doesNotContain("stackTrace");
        assertThat(body).as("No Java class names").doesNotContain("at com.");
        assertThat(body).as("No Java class names").doesNotContain("java.lang");
        assertThat(body).as("No exception names").doesNotContain("Exception");
    }

    @Test
    @DisplayName("sec: POST with wrong Content-Type (text/plain) → 415 or 400, never 500")
    void post_withWrongContentType_neverReturns500() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.TEXT_PLAIN_VALUE)
                        .content("{\"ownerName\":\"Test\"}"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("Wrong Content-Type must return 400 or 415, never 500")
                .isIn(400, 415);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("stackTrace")
                .doesNotContain("Exception");
    }

    // ── Eventual consistency gap (behavioral documentation) ────────────────────

    @Test
    @DisplayName("sec: createAccount returns aggregate state directly — projection lag does not cause data loss")
    // [SECURITY] POST /accounts bypasses the projection and reads from the aggregate state.
    // This is by design: the projection has eventual consistency lag, so reading it immediately
    // after creation would return 404. The service layer must NEVER return stale/missing data for creates.
    void createAccount_returnsAggregateState_notProjection() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"Hank\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Response must contain the account data even before projection processes AccountCreated
        assertThat(body).contains("\"ownerName\":\"Hank\"");
        assertThat(body).contains("\"balanceCents\":0");
        assertThat(body).contains("\"id\":");
    }

    // ── Concurrent transfer + withdrawal race ───────────────────────────────────

    @Test
    @DisplayName("sec: concurrent transfer and withdrawal from same source — balance never negative")
    // [SECURITY] Two concurrent operations on the same account must not produce negative balance
    // even if both initially see sufficient funds. Optimistic locking forces serialization.
    void concurrentTransferAndWithdrawal_balanceNeverNegative() throws InterruptedException {
        String sourceId = createAccountUnchecked("Source");
        String targetId = createAccountUnchecked("Target");
        depositUnchecked(sourceId, 1000);

        // Both threads try to take 800 cents — only one should succeed
        Thread transferThread = new Thread(() -> {
            try {
                mockMvc.perform(post("/api/accounts/" + sourceId + "/transfer")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"toAccountId\":\"" + targetId + "\",\"amountCents\":800,\"description\":\"transfer\"}"))
                        .andReturn();
            } catch (Exception ignored) {}
        });

        Thread withdrawThread = new Thread(() -> {
            try {
                mockMvc.perform(post("/api/accounts/" + sourceId + "/withdraw")
                                .contentType(MediaType.APPLICATION_JSON_VALUE)
                                .content("{\"amountCents\":800,\"description\":\"withdraw\"}"))
                        .andReturn();
            } catch (Exception ignored) {}
        });

        transferThread.start();
        withdrawThread.start();
        transferThread.join(10_000);
        withdrawThread.join(10_000);

        // Verify balance via events — sum of all credit/debit events must be non-negative
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE aggregate_id = ?::uuid", Integer.class, sourceId);
        assertThat(eventCount).isGreaterThanOrEqualTo(2); // at least AccountCreated + deposit

        // Direct balance reconstruction from events to verify invariant
        long balanceFromEvents = jdbcTemplate.queryForList(
                "SELECT event_type, payload->>'amountCents' as amount FROM events WHERE aggregate_id = ?::uuid ORDER BY version ASC",
                sourceId).stream().mapToLong(row -> {
            String type = (String) row.get("event_type");
            String amountStr = (String) row.get("amount");
            if (amountStr == null) return 0L;
            long amount = Long.parseLong(amountStr);
            return switch (type) {
                case "MoneyDeposited", "MoneyReceived" -> amount;
                case "MoneyWithdrawn", "MoneyTransferred" -> -amount;
                default -> 0L;
            };
        }).sum();

        assertThat(balanceFromEvents)
                .as("Balance computed from event stream must never be negative — optimistic locking must serialize concurrent writes")
                .isGreaterThanOrEqualTo(0L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createAccount(String ownerName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"ownerName\":\"" + ownerName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }

    private String createAccountUnchecked(String ownerName) {
        try {
            return createAccount(ownerName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void deposit(String id, long amountCents) throws Exception {
        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON_VALUE)
                        .content("{\"amountCents\":" + amountCents + ",\"description\":\"Deposit\"}"))
                .andExpect(status().isOk());
    }

    private void depositUnchecked(String id, long amountCents) {
        try {
            deposit(id, amountCents);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
