package com.example.calls;

public class ChainFactory {
    public static ChainStep create(DependencyService dependencyService) {
        return new ChainStep(dependencyService);
    }
}
