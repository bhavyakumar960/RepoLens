package com.example.calls;

public class ChainStep {
    private final DependencyService dependencyService;

    public ChainStep(DependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    public void finalizeCall() {
        dependencyService.process("chain");
    }
}
