package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Whitelist registry mapping event type names to their Java classes.
 * Only registered types can be deserialized — unknown types fail fast.
 *
 * // [SECURITY] Jackson Deserialization Safety — whitelist-only type resolution
 * // Prevents gadget-chain attacks (CVE-2017-7525) by rejecting any type not explicitly registered
 */
public class EventTypeRegistry {

    private final Map<String, Class<? extends DomainEvent>> registry;

    private EventTypeRegistry(Map<String, Class<? extends DomainEvent>> registry) {
        this.registry = Collections.unmodifiableMap(registry);
    }

    /**
     * Resolves a type name to its registered class.
     *
     * @param typeName the event type name
     * @return the registered class
     * @throws IllegalArgumentException if the type is not registered — no fallback
     */
    public Class<? extends DomainEvent> resolve(String typeName) {
        Class<? extends DomainEvent> clazz = registry.get(typeName);
        if (clazz == null) {
            // [SECURITY] Unknown type rejected immediately — no dynamic class loading, no fallback
            throw new IllegalArgumentException(
                    "Unknown event type: '" + typeName + "'. Only whitelisted types are allowed.");
        }
        return clazz;
    }

    public boolean isRegistered(String typeName) {
        return registry.containsKey(typeName);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Class<? extends DomainEvent>> map = new HashMap<>();

        public Builder register(String typeName, Class<? extends DomainEvent> clazz) {
            map.put(typeName, clazz);
            return this;
        }

        public EventTypeRegistry build() {
            return new EventTypeRegistry(new HashMap<>(map));
        }
    }
}
