package io.github.warleysr.autopix.domain;

public class DonorInfo {

    private final String donor;
    private final float total;
    private final int ordersCount;

    public DonorInfo(String donor, float total) {
        this(donor, total, 0);
    }

    public DonorInfo(String donor, float total, int ordersCount) {
        this.donor = donor;
        this.total = total;
        this.ordersCount = ordersCount;
    }

    public String getDonor() {
        return this.donor;
    }

    public float getTotal() {
        return this.total;
    }

    public int getOrdersCount() {
        return this.ordersCount;
    }
}
