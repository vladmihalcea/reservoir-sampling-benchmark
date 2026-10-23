package com.vladmihalcea.phd.reservoir.starvation;

/**
 * The kind of thread that runs a client of the {@link StarvationDriver}. Platform threads map one-to-one
 * to OS threads; virtual threads are scheduled by the JDK onto a small set of carrier threads and park
 * cheaply while they wait, which is what lets thousands of them compete for one connection pool.
 *
 * @author Vlad Mihalcea
 */
public enum ThreadKind {

    PLATFORM,
    VIRTUAL;

    public Thread.Builder builder() {
        return this == VIRTUAL ? Thread.ofVirtual() : Thread.ofPlatform();
    }

    public String id() {
        return name().toLowerCase();
    }
}
