package com.example.calls;

public class FieldCaller {
    private DependencyService dependencyService;

    public void callField() {
        dependencyService.process("first");
        this.dependencyService.process("second");
    }
}
