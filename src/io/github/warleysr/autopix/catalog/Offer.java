package io.github.warleysr.autopix.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

public final class Offer {

    private final String id;
    private final String productId;
    private final OfferType type;
    private final BigDecimal price;
    private final String durationString;
    private final Duration duration;
    private final boolean enabled;

    public Offer(String id, String productId, OfferType type, BigDecimal price, String durationString, boolean enabled) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Offer id cannot be null or empty.");
        }
        this.id = id.trim();
        this.productId = productId != null ? productId.trim() : "";
        this.type = type != null ? type : OfferType.ONE_TIME;
        this.price = price != null ? price.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.durationString = durationString != null ? durationString.trim() : null;
        this.duration = parseDuration(this.durationString);
        this.enabled = enabled;
    }

    private static Duration parseDuration(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.toLowerCase().trim();
        try {
            if (s.endsWith("d")) {
                long days = Long.parseLong(s.substring(0, s.length() - 1));
                return Duration.ofDays(days);
            }
            if (s.endsWith("h")) {
                long hours = Long.parseLong(s.substring(0, s.length() - 1));
                return Duration.ofHours(hours);
            }
            if (s.endsWith("m")) {
                long mins = Long.parseLong(s.substring(0, s.length() - 1));
                return Duration.ofMinutes(mins);
            }
            if (s.endsWith("s")) {
                long secs = Long.parseLong(s.substring(0, s.length() - 1));
                return Duration.ofSeconds(secs);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    public String getId() {
        return id;
    }

    public String getProductId() {
        return productId;
    }

    public OfferType getType() {
        return type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getDurationString() {
        return durationString;
    }

    public Duration getDuration() {
        return duration;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
