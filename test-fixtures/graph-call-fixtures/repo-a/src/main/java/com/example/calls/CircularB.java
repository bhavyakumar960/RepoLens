package com.example.calls;

public class CircularB {
    private final CircularA circularA;

    public CircularB(CircularA circularA) {
        this.circularA = circularA;
    }

    public void pong() {
        circularA.ping();
    }
}
