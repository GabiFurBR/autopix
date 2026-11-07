package io.github.warleysr.autopix.api;

public enum OrderStatus {
    CREATED,
    PENDING,
    PAYMENT_PENDING,
    PAID,
    DELIVERING,
    DELIVERED,
    FAILED,
    DELIVERY_FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    NEEDS_REVIEW;

    public boolean isTerminal() {
        return this == DELIVERED || this == EXPIRED || this == CANCELLED || this == REFUNDED || this == FAILED;
    }
}
