package com.example.irudpchat.irudp;

/** What a sender or a receiver needs from its endpoint: a way to send, a clock and the shared timer wheel. */
public interface Link {
    void send(byte[] packet);

    /** Monotonic time in microseconds. */
    long nowUs();

    TimingWheel wheel();
}
