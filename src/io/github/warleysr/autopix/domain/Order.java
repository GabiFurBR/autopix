package io.github.warleysr.autopix.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.github.warleysr.autopix.api.OrderStatus;

public class Order {

    private int id;
    private UUID playerUuid;
    private String player;
    private String product;
    private BigDecimal price;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private String currency = "BRL";
    private String couponSnapshot;
    private OrderStatus status = OrderStatus.CREATED;
    private Date created;
    private Instant updatedAt;
    private Instant expiresAt;
    private String transaction;
    private List<OrderItem> items = new ArrayList<>();

    public Order(String player, String product, float value, long created) {
        this(player, null, product,
                BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                created);
    }

    public Order(String player, UUID playerUuid, String product,
                 BigDecimal price, BigDecimal subtotal, BigDecimal discount, long created) {
        this.player = player;
        this.playerUuid = playerUuid;
        this.product = product;
        this.price = price != null ? price.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.subtotal = subtotal != null ? subtotal.setScale(2, RoundingMode.HALF_UP) : this.price;
        this.discount = discount != null ? discount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.created = new Date(created);
        this.updatedAt = Instant.ofEpochMilli(created);
    }

    public int getId() {
        return id;
    }

    public String getOrderId() {
        return Integer.toString(id);
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayer() {
        return player;
    }

    public String getPlayerName() {
        return player;
    }

    public String getProduct() {
        return product;
    }

    public float getPrice() {
        return price.floatValue();
    }

    public BigDecimal getTotal() {
        return price;
    }

    public void setTotal(BigDecimal total) {
        if (total != null) {
            this.price = total.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public BigDecimal getSubtotal() {
        return subtotal != null ? subtotal : price;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public BigDecimal getDiscount() {
        return discount != null ? discount : BigDecimal.ZERO;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCouponSnapshot() {
        return couponSnapshot;
    }

    public void setCouponSnapshot(String couponSnapshot) {
        this.couponSnapshot = couponSnapshot;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Date getCreated() {
        return created;
    }

    public Instant getCreatedAt() {
        return created != null ? created.toInstant() : Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt != null ? updatedAt : getCreatedAt();
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getTransaction() {
        return transaction;
    }

    public void setTransaction(String transaction) {
        this.transaction = transaction;
        if (transaction != null) {
            this.status = OrderStatus.PAID;
        }
    }

    public boolean isValidated() {
        return transaction != null;
    }

    public Collection<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<OrderItem> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }
}
