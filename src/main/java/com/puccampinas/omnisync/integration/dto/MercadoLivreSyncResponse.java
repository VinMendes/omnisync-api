package com.puccampinas.omnisync.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MercadoLivreSyncResponse {

    @JsonProperty("seller_user_id")
    private String sellerUserId;

    @JsonProperty("system_client_id")
    private Long systemClientId;

    @JsonProperty("total_listings")
    private int totalListings;

    @JsonProperty("synced_products")
    private int syncedProducts;

    private int created;
    private int updated;
    private int reactivated;
    private int deactivated;

    public String getSellerUserId() {
        return sellerUserId;
    }

    public void setSellerUserId(String sellerUserId) {
        this.sellerUserId = sellerUserId;
    }

    public Long getSystemClientId() {
        return systemClientId;
    }

    public void setSystemClientId(Long systemClientId) {
        this.systemClientId = systemClientId;
    }

    public int getTotalListings() {
        return totalListings;
    }

    public void setTotalListings(int totalListings) {
        this.totalListings = totalListings;
    }

    public int getSyncedProducts() {
        return syncedProducts;
    }

    public void setSyncedProducts(int syncedProducts) {
        this.syncedProducts = syncedProducts;
    }

    public int getCreated() {
        return created;
    }

    public void setCreated(int created) {
        this.created = created;
    }

    public int getUpdated() {
        return updated;
    }

    public void setUpdated(int updated) {
        this.updated = updated;
    }

    public int getReactivated() {
        return reactivated;
    }

    public void setReactivated(int reactivated) {
        this.reactivated = reactivated;
    }

    public int getDeactivated() {
        return deactivated;
    }

    public void setDeactivated(int deactivated) {
        this.deactivated = deactivated;
    }
}
