package com.query.insight.common;

import java.security.SecureRandom;

public final class PublicIdGenerator {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicIdGenerator() {
    }

    public static String next() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        char[] value = new char[26];
        int buffer = 0;
        int bits = 0;
        int source = 0;
        for (int index = 0; index < value.length; index++) {
            while (bits < 5) {
                buffer = (buffer << 8) | (source < bytes.length ? bytes[source++] & 0xff : 0);
                bits += 8;
            }
            bits -= 5;
            value[index] = CROCKFORD[(buffer >> bits) & 31];
        }
        value[0] = CROCKFORD[value[0] % 8];
        return new String(value);
    }
}
