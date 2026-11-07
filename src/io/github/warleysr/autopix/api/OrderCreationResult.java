package io.github.warleysr.autopix.api;

import io.github.warleysr.autopix.domain.Order;

public class OrderCreationResult {

    private final boolean successful;
    private final Order order;
    private final Payment payment;
    private final String errorCode;
    private final String message;

    public OrderCreationResult(boolean successful, Order order, Payment payment, String errorCode, String message) {
        this.successful = successful;
        this.order = order;
        this.payment = payment;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static OrderCreationResult success(Order order, Payment payment) {
        return new OrderCreationResult(true, order, payment, null, "Pedido criado com sucesso.");
    }

    public static OrderCreationResult failure(String errorCode, String message) {
        return new OrderCreationResult(false, null, null, errorCode, message);
    }

    public boolean isSuccessful() {
        return successful;
    }

    public Order getOrder() {
        return order;
    }

    public Payment getPayment() {
        return payment;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }
}
