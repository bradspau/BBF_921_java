package org.tmforum.intent.engine;

public record EngineStatus(boolean ready, String reason) {
    public static EngineStatus ok() { return new EngineStatus(true, null); }
    public static EngineStatus notReady(String reason) { return new EngineStatus(false, reason); }
}
