package io.github.warleysr.autopix.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Subscription {

    private final String id;
    private final UUID playerUuid;
    private final String productId;
    private final String offerId;
    private final String providerSubscriptionId;
    private final SubscriptionStatus status;
    private final BigDecimal amount;
    private final String frequency;
    private final Instant createdAt;
    private final Instant nextBillingAt;
    private final Instant cancelledAt;

    public Subscription(String id, UUID playerUuid, String productId, String offerId,
                        String providerSubscriptionId, SubscriptionStatus status, BigDecimal amount,
                        String frequency, Instant createdAt, Instant nextBillingAt, Instant cancelledAt) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.productId = productId;
        this.offerId = offerId;
        this.providerSubscriptionId = providerSubscriptionId;
        this.status = status != null ? status : SubscriptionStatus.PENDING;
        this.amount = amount;
        this.frequency = frequency != null ? frequency : "monthly";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.nextBillingAt = nextBillingAt;
        this.cancelledAt = cancelledAt;
    }

    public String getId() {
        return id;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getProductId() {
        return productId;
    }

    public String getOfferId() {
        return offerId;
    }

    public String getProviderSubscriptionId() {
        return providerSubscriptionId;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getFrequency() {
        return frequency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextBillingAt() {
        return nextBillingAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
