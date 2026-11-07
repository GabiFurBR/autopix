package io.github.warleysr.autopix.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

import io.github.warleysr.autopix.domain.CartItem;

public interface Cart {

    UUID getOwner();

    Collection<CartItem> getItems();

    BigDecimal getSubtotal();

    BigDecimal getDiscount();

    BigDecimal getTotal();

    String getCoupon();

    int getItemCount();
}
