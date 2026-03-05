package com.puccampinas.omnisync.core.systemClient.dto;

import java.util.Map;

public class SystemClientUpdateRequest {

    private String name;
    private String document;
    private Map<String, Object> resource;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public Map<String, Object> getResource() {
        return resource;
    }

    public void setResource(Map<String, Object> resource) {
        this.resource = resource;
    }
}
