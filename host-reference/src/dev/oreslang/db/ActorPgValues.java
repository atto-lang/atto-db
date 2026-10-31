package dev.oreslang.db;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class ActorPgValues {
    static Object copyValue(Object value) {
        if (value == null || value instanceof Integer || value instanceof Long ||
            value instanceof Boolean || value instanceof UUID ||
            value instanceof java.time.LocalDate || value instanceof java.time.LocalDateTime) return value;
        if (value instanceof String s && s.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024) return s;
        if (value instanceof java.math.BigDecimal b && b.precision() <= 4096 &&
            Math.abs((long) b.scale()) <= 10000) return b;
        if (value instanceof Double d && Double.isFinite(d)) return d;
        if (value instanceof Float f && Float.isFinite(f)) return f;
        if (value instanceof byte[] bytes && bytes.length <= 1024 * 1024) return new ActorPgPool.Bytes(bytes);
        if (value instanceof ActorPgPool.Bytes b && b.value().length <= 1024 * 1024) return new ActorPgPool.Bytes(b.value());
        throw new IllegalArgumentException("unsupported or oversized database value");
    }

    /** Conservative admission estimate: one submission has a separate aggregate byte limit. */
    static int estimatedArgumentBytes(Object value) {
        if (value == null) return 1;
        if (value instanceof String s) return s.getBytes(StandardCharsets.UTF_8).length;
        if (value instanceof ActorPgPool.Bytes b) return b.value().length;
        if (value instanceof java.math.BigDecimal b)
            return Math.addExact(Math.addExact(b.precision(), Math.abs(b.scale())), 16);
        return 64;
    }
 }
