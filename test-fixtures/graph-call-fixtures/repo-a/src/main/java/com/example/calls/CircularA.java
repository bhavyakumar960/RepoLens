package com.example.calls;

public class CircularA {
    private final CircularB circularB;

    public CircularA(CircularB circularB) {
        this.circularB = circularB;
    }

    public void ping() {
        circularB.pong();
    }
}
