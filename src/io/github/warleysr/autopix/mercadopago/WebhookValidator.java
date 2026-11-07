package io.github.warleysr.autopix.mercadopago;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic validator for Mercado Pago webhook notifications.
 * Implements the official Mercado Pago HMAC-SHA256 manifest signature verification:
 * manifest = "id:[data.id_lower];request-id:[x-request-id];ts:[ts];"
 */
public final class WebhookValidator {

    private static final String HMAC_SHA256 = "HmacSHA256";
    public static final long DEFAULT_MAX_SKEW_SECONDS = 300L;

    private WebhookValidator() {
    }

    public static boolean validateSignature(String xSignature, String xRequestId, String dataId, String secret) {
        return validateSignature(xSignature, xRequestId, dataId, secret, DEFAULT_MAX_SKEW_SECONDS);
    }

    public static boolean validateSignature(String xSignature, String xRequestId, String dataId,
                                            String secret, long maxSkewSeconds) {
        if (xSignature == null || xSignature.trim().isEmpty()) return false;
        if (xRequestId == null || xRequestId.trim().isEmpty()) return false;
        if (dataId == null || dataId.trim().isEmpty()) return false;
        if (secret == null || secret.trim().isEmpty()) return false;

        String ts = null;
        String v1 = null;

        String[] parts = xSignature.split(",");
        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if ("ts".equalsIgnoreCase(key)) {
                    ts = val;
                } else if ("v1".equalsIgnoreCase(key)) {
                    v1 = val;
                }
            }
        }

        if (ts == null || ts.isEmpty() || v1 == null || v1.isEmpty()) {
            return false;
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(ts);
            if (timestampSeconds <= 0L) return false;
        } catch (NumberFormatException ex) {
            return false;
        }

        long effectiveSkew = maxSkewSeconds > 0L ? maxSkewSeconds : DEFAULT_MAX_SKEW_SECONDS;
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (Math.abs(nowSeconds - timestampSeconds) > effectiveSkew) {
            return false;
        }

        String manifest = buildManifest(dataId, xRequestId, ts);
        String computedHex = hmacSha256Hex(secret.trim(), manifest);
        if (computedHex == null) {
            return false;
        }

        byte[] expectedBytes = computedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    public static String buildManifest(String dataId, String xRequestId, String ts) {
        String normId = dataId != null ? dataId.trim().toLowerCase(Locale.ROOT) : "";
        String normReq = xRequestId != null ? xRequestId.trim() : "";
        String normTs = ts != null ? ts.trim() : "";
        return "id:" + normId + ";request-id:" + normReq + ";ts:" + normTs + ";";
    }

    public static String computeSignatureHeader(String xRequestId, String dataId, long timestampSeconds, String secret) {
        String ts = Long.toString(timestampSeconds);
        String manifest = buildManifest(dataId, xRequestId, ts);
        String v1 = hmacSha256Hex(secret != null ? secret.trim() : "", manifest);
        return "ts=" + ts + ",v1=" + v1;
    }

    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }
}
