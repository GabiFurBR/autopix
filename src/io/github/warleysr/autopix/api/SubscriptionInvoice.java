package io.github.warleysr.autopix.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public final class SubscriptionInvoice {

    private final String invoiceId;
    private final String subscriptionId;
    private final int cycleNumber;
    private final Integer orderId;
    private final String providerPaymentId;
    private final BigDecimal amount;
    private final String currency;
    private final String status;
    private final Instant billingDate;
    private final Instant dueDate;
    private final Instant paidAt;
    private final Instant createdAt;

    public SubscriptionInvoice(String invoiceId, String subscriptionId, int cycleNumber,
                               Integer orderId, String providerPaymentId, BigDecimal amount,
                               String currency, String status, Instant billingDate,
                               Instant dueDate, Instant paidAt, Instant createdAt) {
        this.invoiceId = invoiceId;
        this.subscriptionId = subscriptionId;
        this.cycleNumber = Math.max(1, cycleNumber);
        this.orderId = orderId;
        this.providerPaymentId = providerPaymentId;
        this.amount = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency != null ? currency : "BRL";
        this.status = status != null ? status : "PENDING";
        this.billingDate = billingDate;
        this.dueDate = dueDate;
        this.paidAt = paidAt;
        this.createdAt = createdAt;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public int getCycleNumber() {
        return cycleNumber;
    }

    public Integer getOrderId() {
        return orderId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public boolean isPaid() {
        return "PAID".equalsIgnoreCase(status);
    }

    public Instant getBillingDate() {
        return billingDate;
    }

    public Instant getDueDate() {
        return dueDate;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
