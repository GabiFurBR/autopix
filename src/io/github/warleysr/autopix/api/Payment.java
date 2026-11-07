package io.github.warleysr.autopix.api;

import java.time.Instant;

public class Payment {

    private final String id;
    private final String providerId;
    private final PaymentStatus status;
    private final String qrCode;
    private final String copyPasteCode;
    private final String paymentUrl;
    private final Instant expirationAt;

    public Payment(String id, String providerId, PaymentStatus status,
                   String qrCode, String copyPasteCode, String paymentUrl, Instant expirationAt) {
        this.id = id;
        this.providerId = providerId;
        this.status = status != null ? status : PaymentStatus.PENDING;
        this.qrCode = qrCode;
        this.copyPasteCode = copyPasteCode != null ? copyPasteCode : qrCode;
        this.paymentUrl = paymentUrl;
        this.expirationAt = expirationAt;
    }

    public String getId() {
        return id;
    }

    public String getProviderId() {
        return providerId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getQrCode() {
        return qrCode;
    }

    public String getCopyPasteCode() {
        return copyPasteCode;
    }

    public String getPaymentUrl() {
        return paymentUrl;
    }

    public Instant getExpirationAt() {
        return expirationAt;
    }
}
