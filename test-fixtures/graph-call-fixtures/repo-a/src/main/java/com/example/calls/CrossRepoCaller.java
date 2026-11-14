package com.example.calls;

import com.external.ExternalService;

public class CrossRepoCaller {
    private final ExternalService externalService;

    public CrossRepoCaller(ExternalService externalService) {
        this.externalService = externalService;
    }

    public void callExternal() {
        externalService.remote("remote");
    }
}
