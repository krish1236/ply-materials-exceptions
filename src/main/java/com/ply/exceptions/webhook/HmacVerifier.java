package com.ply.exceptions.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class HmacVerifier {

    public enum Outcome {
        VALID,
        BAD_SIGNATURE,
        TIMESTAMP_OUT_OF_WINDOW,
        MALFORMED_HEADER
    }

    private static final String ALGO = "HmacSHA256";

    private final long maxSkewSeconds;

    public HmacVerifier(long maxSkewSeconds) {
        this.maxSkewSeconds = maxSkewSeconds;
    }

    public Outcome verify(String secret, String body, String header, long nowEpochSec) {
        ParsedHeader parsed = parse(header);
        if (parsed == null) {
            return Outcome.MALFORMED_HEADER;
        }
        if (Math.abs(nowEpochSec - parsed.t()) > maxSkewSeconds) {
            return Outcome.TIMESTAMP_OUT_OF_WINDOW;
        }
        String expected = hmacHex(secret, parsed.t() + "." + body);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = parsed.v1().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            return Outcome.BAD_SIGNATURE;
        }
        return Outcome.VALID;
    }

    record ParsedHeader(long t, String v1) {}

    ParsedHeader parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                return null;
            }
            String key = kv[0].trim().toLowerCase();
            String val = kv[1].trim();
            if (key.equals("t")) {
                try {
                    t = Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (key.equals("v1")) {
                v1 = val;
            }
        }
        if (t == null || v1 == null) {
            return null;
        }
        return new ParsedHeader(t, v1);
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte value : raw) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac compute failed", e);
        }
    }
}
