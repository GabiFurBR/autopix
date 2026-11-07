package io.github.warleysr.autopix.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.github.warleysr.autopix.domain.CartItem;

public class CheckoutRequest {

    private final UUID playerUuid;
    private final String idempotencyKey;
    private final String coupon;
    private final boolean giveMap;
    private final BigDecimal customDonationAmount;
    private final String customDonationProductId;
    private final List<CartItem> customItems;

    public CheckoutRequest() {
        this(null, null, null, false, null, null, null);
    }

    public CheckoutRequest(String coupon, boolean giveMap) {
        this(null, null, coupon, giveMap, null, null, null);
    }

    public CheckoutRequest(String coupon, boolean giveMap, BigDecimal customDonationAmount, String customDonationProductId) {
        this(null, null, coupon, giveMap, customDonationAmount, customDonationProductId, null);
    }

    public CheckoutRequest(UUID playerUuid, String idempotencyKey, String coupon, boolean giveMap,
                           BigDecimal customDonationAmount, String customDonationProductId, List<CartItem> customItems) {
        this.playerUuid = playerUuid;
        this.idempotencyKey = idempotencyKey;
        this.coupon = coupon != null && !coupon.trim().isEmpty() ? coupon.trim() : null;
        this.giveMap = giveMap;
        this.customDonationAmount = customDonationAmount;
        this.customDonationProductId = customDonationProductId;
        this.customItems = customItems != null ? Collections.unmodifiableList(new ArrayList<>(customItems)) : null;
    }

    public static CheckoutRequest of(boolean giveMap) {
        return new CheckoutRequest(null, giveMap);
    }

    public static CheckoutRequest withCoupon(String coupon, boolean giveMap) {
        return new CheckoutRequest(coupon, giveMap);
    }

    public static CheckoutRequest customDonation(String productId, BigDecimal amount, boolean giveMap) {
        return new CheckoutRequest(null, giveMap, amount, productId);
    }

    public static CheckoutRequest customDonation(UUID playerUuid, BigDecimal amount) {
        return new CheckoutRequest(playerUuid, null, null, false, amount, "custom_donation", null);
    }

    public static CheckoutRequest customDonation(UUID playerUuid, BigDecimal amount, boolean giveMap) {
        return new CheckoutRequest(playerUuid, null, null, giveMap, amount, "custom_donation", null);
    }

    public static CheckoutRequest forPlayer(UUID playerUuid) {
        return new CheckoutRequest(playerUuid, null, null, false, null, null, null);
    }

    public static CheckoutRequest forPlayer(UUID playerUuid, String coupon) {
        return new CheckoutRequest(playerUuid, null, coupon, false, null, null, null);
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCoupon() {
        return coupon;
    }

    public boolean isGiveMap() {
        return giveMap;
    }

    public BigDecimal getCustomDonationAmount() {
        return customDonationAmount;
    }

    public String getCustomDonationProductId() {
        return customDonationProductId;
    }

    public List<CartItem> getCustomItems() {
        return customItems;
    }
}
