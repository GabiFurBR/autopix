package io.github.warleysr.autopix.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;

public final class CustomDonationConfig {

    private final boolean enabled;
    private final BigDecimal minimum;
    private final BigDecimal maximum;
    private final boolean allowDecimal;
    private final String reward;
    private final String category;
    private final DeliveryConfig delivery;

    public CustomDonationConfig(boolean enabled, BigDecimal minimum, BigDecimal maximum, boolean allowDecimal, String reward) {
        this.enabled = enabled;
        this.minimum = minimum != null ? minimum.setScale(2, RoundingMode.HALF_UP) : new BigDecimal("1.00");
        this.maximum = maximum != null ? maximum.setScale(2, RoundingMode.HALF_UP) : new BigDecimal("1000.00");
        this.allowDecimal = allowDecimal;
        this.reward = reward != null && !reward.trim().isEmpty() ? reward.trim() : "custom_donation_reward";
        this.category = "donations";
        this.delivery = new DeliveryConfig(DeliveryType.SKRIPT, Collections.emptyList(), this.reward);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal getMinimum() {
        return minimum;
    }

    public BigDecimal getMaximum() {
        return maximum;
    }

    public boolean isAllowDecimal() {
        return allowDecimal;
    }

    public String getReward() {
        return reward;
    }

    public String getCategory() {
        return category;
    }

    public DeliveryConfig getDelivery() {
        return delivery;
    }

    public boolean isValidAmount(BigDecimal amount) {
        if (!enabled || amount == null) return false;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return false;
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > 2) {
            return false;
        }
        if (!allowDecimal && stripped.scale() > 0) {
            return false;
        }
        return amount.compareTo(minimum) >= 0 && amount.compareTo(maximum) <= 0;
    }

    public BigDecimal validateAndNormalize(String rawAmount) {
        if (!enabled) {
            throw new IllegalArgumentException("Doações personalizadas estão desativadas no catálogo.");
        }
        if (rawAmount == null || rawAmount.trim().isEmpty()) {
            throw new IllegalArgumentException("O valor da doação personalizada não pode ser vazio.");
        }
        String normalized = rawAmount.trim().replace(',', '.');
        if (!normalized.matches("^\\d+(\\.\\d{1,2})?$")) {
            throw new IllegalArgumentException("Formato de valor inválido para doação personalizada: " + rawAmount);
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Valor numérico inválido para doação: " + rawAmount, ex);
        }
        return validateAndNormalize(parsed);
    }

    public BigDecimal validateAndNormalize(BigDecimal amount) {
        if (!enabled) {
            throw new IllegalArgumentException("Doações personalizadas estão desativadas no catálogo.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("O valor da doação personalizada não foi informado.");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("O valor da doação deve ser maior que zero.");
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > 2) {
            throw new IllegalArgumentException("O valor da doação deve possuir no máximo 2 casas decimais.");
        }
        if (!allowDecimal && stripped.scale() > 0) {
            throw new IllegalArgumentException("Apenas valores inteiros são permitidos para doações personalizadas.");
        }
        if (amount.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("O valor mínimo para doação é R$ " + minimum.toPlainString() + ".");
        }
        if (amount.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("O valor máximo para doação é R$ " + maximum.toPlainString() + ".");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
