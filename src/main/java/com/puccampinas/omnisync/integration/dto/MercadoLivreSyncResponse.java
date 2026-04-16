package com.puccampinas.omnisync.integration.dto;

public class MercadoLivreSyncResponse {

    private String message;

    private int syncedProducts;

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
}
