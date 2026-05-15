package com.chronicle.core.serialization;

import com.chronicle.core.event.DomainEvent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whitelist registry mapping event type names to their Java classes.
 * Only registered types can be deserialized — unknown types fail fast.
 *
 * <p>// [SECURITY] Jackson Deserialization Safety — whitelist-only type resolution
 * Prevents gadget-chain attacks (CVE-2017-7525) by rejecting any type not explicitly registered.
 * // [SECURITY] ConcurrentHashMap for thread-safe registration from multiple threads
 */
public class EventTypeRegistry {

    // [SECURITY] Whitelist-only — no arbitrary class loading, no reflection by name
    private final ConcurrentHashMap<String, Class<? extends DomainEvent>> typeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<? extends DomainEvent>, String> reverseMap = new ConcurrentHashMap<>();

    /**
     * Registers an event type name to its class. Each name must be unique.
     *
     * @param typeName the event type name (used as the discriminator in stored events)
     * @param clazz    the domain event class
     * @throws IllegalArgumentException if typeName is blank or clazz is null
     * @throws IllegalStateException    if typeName is already registered
     */
    public void register(String typeName, Class<? extends DomainEvent> clazz) {
        Objects.requireNonNull(typeName, "typeName must not be null");
        if (typeName.isBlank()) {
            throw new IllegalArgumentException("typeName must not be blank");
        }
        Objects.requireNonNull(clazz, "clazz must not be null");
        // [SECURITY] putIfAbsent is atomic — eliminates TOCTOU between containsKey() and put()
        // A non-atomic containsKey+put pair allows two threads to both pass the check
        // and silently overwrite a registered type with a different class (type confusion attack)
        Class<? extends DomainEvent> existing = typeMap.putIfAbsent(typeName, clazz);
        if (existing != null) {
            throw new IllegalStateException("Event type already registered: " + typeName);
        }
        reverseMap.put(clazz, typeName);
    }

    /**
     * Resolves a type name to its registered class.
     *
     * @param typeName the event type name
     * @return the registered class
     * @throws IllegalArgumentException if the type is not registered — no fallback
     */
    public Class<? extends DomainEvent> resolve(String typeName) {
        Class<? extends DomainEvent> clazz = typeMap.get(typeName);
        if (clazz == null) {
            // [SECURITY] Unknown type rejected immediately — no dynamic class loading, no fallback
            throw new IllegalArgumentException(
                    "Unknown event type: '" + typeName + "'. Only whitelisted types are allowed.");
        }
        return clazz;
    }

    /**
     * Returns the registered type name for a given event class.
     *
     * @param clazz the event class
     * @return registered type name
     * @throws IllegalArgumentException if the class is not registered
     */
    public String typeNameFor(Class<? extends DomainEvent> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String name = reverseMap.get(clazz);
        if (name == null) {
            throw new IllegalArgumentException(
                    "Event class not registered: " + clazz.getName() + ". Register it before use.");
        }
        return name;
    }

    public boolean isRegistered(String typeName) {
        return typeMap.containsKey(typeName);
    }
}
