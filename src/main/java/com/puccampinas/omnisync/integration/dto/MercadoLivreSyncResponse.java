package com.puccampinas.omnisync.integration.dto;

import java.time.Instant;

public class MercadoLivreSyncResponse {

    private String message;

    private int syncedProducts;

    private int createdProducts;

    private int updatedProducts;

    private Instant lastSyncAt;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getSyncedProducts() {
        return syncedProducts;
    }

    public void setSyncedProducts(int syncedProducts) {
        this.syncedProducts = syncedProducts;
    }

    public int getCreatedProducts() {
        return createdProducts;
    }

    public void setCreatedProducts(int createdProducts) {
        this.createdProducts = createdProducts;
    }

    public int getUpdatedProducts() {
        return updatedProducts;
    }

    public void setUpdatedProducts(int updatedProducts) {
        this.updatedProducts = updatedProducts;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(Instant lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }
}
