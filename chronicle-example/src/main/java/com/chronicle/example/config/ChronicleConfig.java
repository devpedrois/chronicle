package com.chronicle.example.config;

import com.chronicle.core.engine.ChronicleEngine;
import com.chronicle.core.projection.ProjectionEngine;
import com.chronicle.core.serialization.EventTypeRegistry;
import com.chronicle.core.serialization.JacksonEventSerializer;
import com.chronicle.core.snapshot.EveryNEventsPolicy;
import com.chronicle.example.domain.BankAccount;
import com.chronicle.example.domain.BankAccountState;
import com.chronicle.example.domain.event.AccountCreated;
import com.chronicle.example.domain.event.MoneyDeposited;
import com.chronicle.example.domain.event.MoneyReceived;
import com.chronicle.example.domain.event.MoneyTransferred;
import com.chronicle.example.domain.event.MoneyWithdrawn;
import com.chronicle.example.projection.AccountBalanceProjection;
import com.chronicle.jdbc.JdbcEventStore;
import com.chronicle.jdbc.JdbcProjectionPositionStore;
import com.chronicle.jdbc.JdbcSnapshotStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Configuration
public class ChronicleConfig {

    // [SECURITY] Primary ObjectMapper — FAIL_ON_UNKNOWN_PROPERTIES=true rejects unknown fields in request bodies.
    // Prevents type confusion attacks where extra fields are silently accepted or processed.
    // NEVER call activateDefaultTyping() / enableDefaultTyping() — gadget chain RCE (CVE-2017-7525).
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // [SECURITY] Reject any unknown field in request bodies — explicit allowlist via record fields
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    // [SECURITY] EventTypeRegistry whitelist — only explicitly registered event types can be deserialized.
    // Prevents gadget-chain attacks (CVE-2017-7525) by rejecting any type not in the whitelist.
    // Unknown types throw IllegalArgumentException — no fallback, no dynamic class loading.
    @Bean
    public EventTypeRegistry eventTypeRegistry() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register("AccountCreated", AccountCreated.class);
        registry.register("MoneyDeposited", MoneyDeposited.class);
        registry.register("MoneyWithdrawn", MoneyWithdrawn.class);
        registry.register("MoneyTransferred", MoneyTransferred.class);
        registry.register("MoneyReceived", MoneyReceived.class);
        return registry;
    }

    // [SECURITY] Jackson Deserialization Safety — JacksonEventSerializer internally configures:
    // FAIL_ON_UNKNOWN_PROPERTIES=true, FAIL_ON_NULL_FOR_PRIMITIVES=true,
    // BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES=true.
    // NEVER call activateDefaultTyping() / enableDefaultTyping() — gadget chain RCE (CVE-2017-7525).
    @Bean
    public JacksonEventSerializer<BankAccountState> jacksonEventSerializer(EventTypeRegistry registry) {
        return new JacksonEventSerializer<>(registry);
    }

    @Bean
    public JdbcEventStore jdbcEventStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        return new JdbcEventStore(jdbcTemplate, txManager);
    }

    @Bean
    public JdbcSnapshotStore jdbcSnapshotStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        return new JdbcSnapshotStore(jdbcTemplate, txManager);
    }

    @Bean
    public JdbcProjectionPositionStore jdbcProjectionPositionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcProjectionPositionStore(jdbcTemplate);
    }

    @Bean
    public EveryNEventsPolicy snapshotPolicy() {
        return new EveryNEventsPolicy(50);
    }

    @Bean
    public ChronicleEngine<BankAccountState> chronicleEngine(
            JdbcEventStore eventStore,
            JdbcSnapshotStore snapshotStore,
            JacksonEventSerializer<BankAccountState> serializer,
            EveryNEventsPolicy snapshotPolicy) {
        return new ChronicleEngine<>(
                eventStore,
                snapshotStore,
                serializer,
                snapshotPolicy,
                new BankAccount(),
                BankAccountState.class);
    }

    @Bean
    public AccountBalanceProjection accountBalanceProjection(
            JdbcTemplate jdbcTemplate,
            JacksonEventSerializer<BankAccountState> serializer,
            PlatformTransactionManager txManager) {
        return new AccountBalanceProjection(jdbcTemplate, serializer, txManager);
    }

    // [SECURITY] initMethod/destroyMethod ensures clean lifecycle — no event duplication on restart.
    // ProjectionEngine.start() guards against double-start via AtomicBoolean compareAndSet.
    // ProjectionEngine.stop() awaits termination (30s) before forcing shutdown.
    @Bean(initMethod = "start", destroyMethod = "stop")
    public ProjectionEngine projectionEngine(
            JdbcEventStore eventStore,
            JdbcProjectionPositionStore positionStore,
            AccountBalanceProjection projection,
            @Value("${chronicle.projection.poll-interval-ms:500}") long pollIntervalMs) {
        return new ProjectionEngine(eventStore, positionStore, List.of(projection), pollIntervalMs);
    }
}
