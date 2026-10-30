package com.smartelder.platform.core.common;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PublicIds {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Pattern PREFIX_PATTERN = Pattern.compile("[a-z][a-z0-9]{0,31}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] LAST_RANDOM = new byte[10];
    private static long lastTimestamp = -1L;

    private PublicIds() {
    }

    public static synchronized String newId(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("prefix must match [a-z][a-z0-9]{0,31}");
        }

        long timestamp = System.currentTimeMillis();
        if (timestamp > lastTimestamp) {
            lastTimestamp = timestamp;
            RANDOM.nextBytes(LAST_RANDOM);
        } else if (!incrementRandom()) {
            do {
                timestamp = System.currentTimeMillis();
            } while (timestamp <= lastTimestamp);
            lastTimestamp = timestamp;
            RANDOM.nextBytes(LAST_RANDOM);
        }

        byte[] bytes = new byte[16];
        long time = lastTimestamp;
        for (int index = 5; index >= 0; index--) {
            bytes[index] = (byte) (time & 0xFF);
            time >>>= 8;
        }
        System.arraycopy(LAST_RANDOM, 0, bytes, 6, LAST_RANDOM.length);
        return prefix + "_" + encode(bytes);
    }

    private static boolean incrementRandom() {
        for (int index = LAST_RANDOM.length - 1; index >= 0; index--) {
            LAST_RANDOM[index]++;
            if (LAST_RANDOM[index] != 0) {
                return true;
            }
        }
        return false;
    }

    private static String encode(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        BigInteger mask = BigInteger.valueOf(31);
        char[] encoded = new char[26];
        for (int index = encoded.length - 1; index >= 0; index--) {
            encoded[index] = CROCKFORD[value.and(mask).intValue()];
            value = value.shiftRight(5);
        }
        return new String(encoded);
    }
}
