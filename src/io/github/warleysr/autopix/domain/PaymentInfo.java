package io.github.warleysr.autopix.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class PaymentInfo {

    private final String paymentId;
    private final String transactionId;
    private final String status;
    private final BigDecimal paidAmountDecimal;
    private final String currencyId;
    private final String externalReference;

    public PaymentInfo(String transactionId, String status, double paidAmount) {
        this(null, transactionId, status,
                BigDecimal.valueOf(paidAmount).setScale(2, RoundingMode.HALF_UP),
                "BRL", null);
    }

    public PaymentInfo(String paymentId, String transactionId, String status,
                       BigDecimal paidAmount, String currencyId, String externalReference) {
        this.paymentId = paymentId;
        this.transactionId = transactionId;
        this.status = status;
        this.paidAmountDecimal = paidAmount != null
                ? paidAmount.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.currencyId = currencyId != null && !currencyId.trim().isEmpty()
                ? currencyId.trim().toUpperCase()
                : "BRL";
        this.externalReference = externalReference != null && !externalReference.trim().isEmpty()
                ? externalReference.trim()
                : null;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getStatus() {
        return status;
    }

    public double getPaidAmount() {
        return paidAmountDecimal.doubleValue();
    }

    public BigDecimal getPaidAmountDecimal() {
        return paidAmountDecimal;
    }

    public String getCurrencyId() {
        return currencyId;
    }

    public String getExternalReference() {
        return externalReference;
    }
}
