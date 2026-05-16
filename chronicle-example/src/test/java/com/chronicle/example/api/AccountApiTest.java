package com.chronicle.example.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountApiTest extends AbstractApiTest {

    @Autowired
    private MockMvc mockMvc;

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
        // events table: TRUNCATE bypasses row-level triggers (BEFORE UPDATE/DELETE)
        jdbcTemplate.execute("TRUNCATE TABLE events");
    }

    @Test
    @DisplayName("Test 1: POST /api/accounts with valid ownerName → 201 with id and ownerName")
    void createAccount_validRequest_returns201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Alice Smith\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.ownerName").value("Alice Smith"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("stackTrace");
    }

    @Test
    @DisplayName("Test 2: POST /api/accounts/{id}/deposit with valid amount → 200, balance increased")
    void deposit_validRequest_returns200WithIncreasedBalance() throws Exception {
        String id = createAccount("Bob");

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":5000,\"description\":\"Salary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(5000));
    }

    @Test
    @DisplayName("Test 3: POST /api/accounts/{id}/withdraw with sufficient balance → 200")
    void withdraw_sufficientBalance_returns200() throws Exception {
        String id = createAccount("Carol");
        deposit(id, 10000);

        mockMvc.perform(post("/api/accounts/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":3000,\"description\":\"Rent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(7000));
    }

    @Test
    @DisplayName("Test 4: POST /api/accounts/{id}/withdraw with insufficient balance → 400 Insufficient funds")
    void withdraw_insufficientBalance_returns400() throws Exception {
        String id = createAccount("Dave");

        mockMvc.perform(post("/api/accounts/" + id + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":1000,\"description\":\"ATM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Insufficient funds"));
    }

    @Test
    @DisplayName("Test 5: GET /api/accounts/{id} → 200 with correct balance from projection")
    void getAccount_afterDeposit_returns200WithCorrectBalance() throws Exception {
        String id = createAccount("Eve");
        deposit(id, 8000);

        // Wait for async projection to process events
        Thread.sleep(600);

        mockMvc.perform(get("/api/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.ownerName").value("Eve"))
                .andExpect(jsonPath("$.balanceCents").value(8000));
    }

    @Test
    @DisplayName("Test 6: GET /api/accounts/{unknown-uuid} → 404")
    void getAccount_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/accounts/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Account not found"));
    }

    @Test
    @DisplayName("Test 7: POST /api/accounts/{id}/deposit with amount 0 → 400 (Bean Validation)")
    void deposit_zeroAmount_returns400() throws Exception {
        String id = createAccount("Frank");

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":0,\"description\":\"Zero\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("Test 8: POST /api/accounts/{id}/deposit with negative amount → 400 (Bean Validation)")
    void deposit_negativeAmount_returns400() throws Exception {
        String id = createAccount("Grace");

        mockMvc.perform(post("/api/accounts/" + id + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":-100,\"description\":\"Neg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("Test 9: POST /api/accounts with blank ownerName → 400 (Bean Validation)")
    void createAccount_blankOwnerName_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    @DisplayName("Test 10: GET /api/accounts/not-a-uuid → 400, NOT 500")
    // [SECURITY] Invalid UUID in path param must return 400, not 500 with stack trace
    void getAccount_invalidUuidFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid parameter format"));
    }

    // --- helpers ---

    private String createAccount(String ownerName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + ownerName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // Extract id: {"id":"<uuid>", ...}
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
}
