package io.github.warleysr.autopix.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.github.warleysr.autopix.api.Cart;
import io.github.warleysr.autopix.domain.CartItem;

public final class CartImpl implements Cart {

    private final UUID owner;
    private final List<CartItem> items;
    private final String coupon;
    private final int discountPercent;
    private final BigDecimal subtotal;
    private final BigDecimal discount;
    private final BigDecimal total;

    public CartImpl(UUID owner, List<CartItem> items, String coupon, int discountPercent) {
        this.owner = owner;
        this.items = items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
        this.coupon = coupon;
        this.discountPercent = Math.max(0, Math.min(99, discountPercent));

        BigDecimal sub = BigDecimal.ZERO;
        for (CartItem item : this.items) {
            sub = sub.add(item.getLineTotalDecimal());
        }
        this.subtotal = sub.setScale(2, RoundingMode.HALF_UP);

        if (this.discountPercent > 0 && this.subtotal.compareTo(BigDecimal.ZERO) > 0) {
            this.discount = this.subtotal.multiply(BigDecimal.valueOf(this.discountPercent))
                    .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
            this.total = this.subtotal.subtract(this.discount).setScale(2, RoundingMode.HALF_UP);
        } else {
            this.discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            this.total = this.subtotal;
        }
    }

    @Override
    public UUID getOwner() {
        return owner;
    }

    @Override
    public Collection<CartItem> getItems() {
        return items;
    }

    @Override
    public BigDecimal getSubtotal() {
        return subtotal;
    }

    @Override
    public BigDecimal getDiscount() {
        return discount;
    }

    @Override
    public BigDecimal getTotal() {
        return total;
    }

    @Override
    public String getCoupon() {
        return coupon;
    }

    @Override
    public int getItemCount() {
        int count = 0;
        for (CartItem item : items) {
            count += item.getQuantity();
        }
        return count;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }
}
