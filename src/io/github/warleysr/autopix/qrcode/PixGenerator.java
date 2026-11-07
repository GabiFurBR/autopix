package io.github.warleysr.autopix.qrcode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public class PixGenerator {

    private static final String PAYLOAD_1 = "00020126";
    private static final String PAYLOAD_2 = "0014BR.GOV.BCB.PIX01";
    private static final String PAYLOAD_3 = "52040000530398654";
    private static final String PAYLOAD_4 = "5802BR59";
    private static final String PAYLOAD_5 = "6008BRASILIA62";
    private static final String PAYLOAD_6 = "6304";
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    public static boolean isValidCurrencyAmount(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        if (amount.compareTo(MIN_AMOUNT) < 0) {
            return false;
        }
        return amount.stripTrailingZeros().scale() <= 2;
    }

    public static String generatePayload(String key, String name, String description, float price) {
        if (!Float.isFinite(price) || price < 0.01f) {
            return null;
        }
        return generatePayload(key, name, description, new BigDecimal(Float.toString(price)));
    }

    public static String generatePayload(String key, String name, String description, BigDecimal price) {
        if (key == null || key.trim().isEmpty() || !isValidCurrencyAmount(price)) {
            return null;
        }
        String cleanKey = key.trim();
        String cleanName = sanitizeField(name != null ? name : "AutoPix", 25);
        String cleanDesc = sanitizeField(description != null ? description : "***", 25);

        String priceFmt = price.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        String desc = "05" + String.format(Locale.ROOT, "%02d", cleanDesc.length()) + cleanDesc;
        String merchant = PAYLOAD_2 + String.format(Locale.ROOT, "%02d", cleanKey.length()) + cleanKey;

        String payload = PAYLOAD_1 + String.format(Locale.ROOT, "%02d", merchant.length()) + merchant
                + PAYLOAD_3 + String.format(Locale.ROOT, "%02d", priceFmt.length()) + priceFmt
                + PAYLOAD_4 + String.format(Locale.ROOT, "%02d", cleanName.length()) + cleanName
                + PAYLOAD_5 + String.format(Locale.ROOT, "%02d", desc.length()) + desc
                + PAYLOAD_6;

        return payload + String.format(Locale.ROOT, "%04X", CRC16.calculateCRC(payload)).toUpperCase(Locale.ROOT);
    }

    private static String sanitizeField(String value, int maxLength) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            trimmed = "***";
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
