package io.github.warleysr.autopix.api;

import io.github.warleysr.autopix.domain.Order;

public class SubscriptionResult {

    private final boolean successful;
    private final Subscription subscription;
    private final SubscriptionInvoice invoice;
    private final Order order;
    private final Payment payment;
    private final String message;

    public SubscriptionResult(boolean successful, Subscription subscription, String message) {
        this(successful, subscription, null, null, null, message);
    }

    public SubscriptionResult(boolean successful, Subscription subscription, SubscriptionInvoice invoice,
                              Order order, Payment payment, String message) {
        this.successful = successful;
        this.subscription = subscription;
        this.invoice = invoice;
        this.order = order;
        this.payment = payment;
        this.message = message;
    }

    public static SubscriptionResult success(Subscription subscription, String message) {
        return new SubscriptionResult(true, subscription, null, null, null, message);
    }

    public static SubscriptionResult success(Subscription subscription, SubscriptionInvoice invoice,
                                             Order order, Payment payment, String message) {
        return new SubscriptionResult(true, subscription, invoice, order, payment, message);
    }

    public static SubscriptionResult failure(String message) {
        return new SubscriptionResult(false, null, null, null, null, message);
    }

    public boolean isSuccessful() {
        return successful;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public SubscriptionInvoice getInvoice() {
        return invoice;
    }

    public Order getOrder() {
        return order;
    }

    public Payment getPayment() {
        return payment;
    }

    public String getMessage() {
        return message;
    }
}
