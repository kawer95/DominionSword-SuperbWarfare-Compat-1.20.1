package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraftforge.common.ForgeConfigSpec;

public final class SuperbWarfareCompatConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue LAG_TRACE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue LAG_TRACE_WARN_MS;
    public static final ForgeConfigSpec.BooleanValue FLIGHT_CONTROL_TRACE_ENABLED;
    public static final ForgeConfigSpec.IntValue FLIGHT_CONTROL_TRACE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue MULTI_STAGE_K_TURN_ENABLED;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("debug");
        LAG_TRACE_ENABLED = builder
                .comment("Enable detailed lag tracing for Dominion Sword Superb Warfare fleet control.")
                .define("lagTrace", false);
        LAG_TRACE_WARN_MS = builder
                .comment("Warn when a traced fleet-control section takes at least this many milliseconds.")
                .defineInRange("lagTraceWarnMs", 8.0D, 0.1D, 10_000.0D);
        FLIGHT_CONTROL_TRACE_ENABLED = builder
                .comment("Log complete helicopter flight-control state for diagnosis. Enabled temporarily by default.")
                .define("flightControlTrace", true);
        FLIGHT_CONTROL_TRACE_INTERVAL_TICKS = builder
                .comment("Minimum ticks between flight-control trace lines for each helicopter.")
                .defineInRange("flightControlTraceIntervalTicks", 5, 1, 200);
        builder.pop();
        builder.push("driving");
        MULTI_STAGE_K_TURN_ENABLED = builder
                .comment("Enable multi-stage K-turns in tight spaces. Disabled by default; single reverse-steer K-turn remains active.")
                .define("multiStageKTurn", false);
        builder.pop();
        SPEC = builder.build();
    }

    private SuperbWarfareCompatConfig() {}

    public static boolean lagTraceEnabled() {
        return LAG_TRACE_ENABLED.get();
    }

    public static double lagTraceWarnMs() {
        return LAG_TRACE_WARN_MS.get();
    }

    public static boolean flightControlTraceEnabled() {
        return FLIGHT_CONTROL_TRACE_ENABLED.get();
    }

    public static int flightControlTraceIntervalTicks() {
        return FLIGHT_CONTROL_TRACE_INTERVAL_TICKS.get();
    }

    public static boolean multiStageKTurnEnabled() {
        return MULTI_STAGE_K_TURN_ENABLED.get();
    }
}
