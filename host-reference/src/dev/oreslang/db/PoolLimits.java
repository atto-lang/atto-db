package dev.oreslang.db;

/** Bounds for host-side actor database admission and result materialization. */
public record PoolLimits(int connections, int maxTotal, int maxPerTenant, int maxRows, int maxResultBytes) {
    public PoolLimits {
        if (connections < 1 || maxTotal < 1 || maxPerTenant < 1 || maxPerTenant > maxTotal || maxRows < 1 || maxResultBytes < 1)
            throw new IllegalArgumentException("invalid database pool limits");
    }
}
