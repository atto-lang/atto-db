package dev.oreslang.db;
public final class PoolLimitsTest {
    public static void main(String[] args) {
        new PoolLimits(4, 100, 10, 1000, 4194304);
        reject(0, 10, 10, 100, 1024);
        reject(2, 0, 10, 100, 1024);
        reject(2, 10, 11, 100, 1024);
        reject(2, 10, 1, 0, 1024);
        reject(2, 10, 1, 100, 0);
        System.out.println("POOL_LIMITS_PASS");
    }
    private static void reject(int c, int total, int tenant, int rows, int bytes) {
        try {
            new PoolLimits(c, total, tenant, rows, bytes);
            throw new AssertionError("invalid pool limits accepted");
        } catch (IllegalArgumentException expected) {
            // fail closed
        }
    }
}
