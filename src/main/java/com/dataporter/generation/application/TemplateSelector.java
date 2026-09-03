package com.dataporter.generation.application;

import com.dataporter.generation.domain.TemplateSelection;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TemplateSelector {
    // Parameters depend only on (seed, collection, count); one entry per collection per run.
    private final Map<String, Parameters> parameterCache = new ConcurrentHashMap<>();

    public long select(TemplateSelection strategy, long seed, String collection,
                       long iteration, long templateCount) {
        if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
        if (templateCount < 1) throw new IllegalArgumentException("templateCount must be positive");
        long position = iteration % templateCount;
        if (strategy == TemplateSelection.SEQUENTIAL || templateCount == 1) return position;

        long cycle = iteration / templateCount;
        Parameters parameters = parameters(seed, collection, templateCount);
        long offset = multiplyModulo(parameters.cycleStep(), cycle, templateCount, parameters.offset());
        return multiplyModulo(parameters.multiplier(), position, templateCount, offset);
    }

    private Parameters parameters(long seed, String collection, long count) {
        String key = seed + "\0" + collection + "\0" + count;
        Parameters cached = parameterCache.get(key);
        if (cached != null) return cached;
        byte[] digest = hash(seed, collection);
        long multiplier = coprime(positiveLong(digest, 0) % count, count);
        long offset = positiveLong(digest, Long.BYTES) % count;
        long cycleStep = coprime(positiveLong(digest, Long.BYTES * 2) % count, count);
        Parameters computed = new Parameters(multiplier, offset, cycleStep);
        parameterCache.put(key, computed);
        return computed;
    }

    private static long coprime(long candidate, long count) {
        if (candidate == 0) candidate = 1;
        while (gcd(candidate, count) != 1) {
            candidate++;
            if (candidate == count) candidate = 1;
        }
        return candidate;
    }

    private static long multiplyModulo(long multiplier, long position, long modulus, long offset) {
        // Below 2^31 the product of two values under the modulus cannot overflow a long,
        // and non-negative long arithmetic matches the BigInteger result exactly.
        if (modulus < (1L << 31)) {
            long product = multiplier * position + offset;
            return product % modulus;
        }
        BigInteger value = BigInteger.valueOf(multiplier).multiply(BigInteger.valueOf(position))
                .add(BigInteger.valueOf(offset)).mod(BigInteger.valueOf(modulus));
        return value.longValueExact();
    }

    private static long positiveLong(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Long.BYTES).getLong() & Long.MAX_VALUE;
    }

    private static long gcd(long left, long right) {
        while (right != 0) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return Math.abs(left);
    }

    private static byte[] hash(long seed, String collection) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(seed).array());
            digest.update(collection.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Parameters(long multiplier, long offset, long cycleStep) { }
}
