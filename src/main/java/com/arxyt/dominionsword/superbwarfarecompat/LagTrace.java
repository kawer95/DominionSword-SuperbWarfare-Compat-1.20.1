package com.arxyt.dominionsword.superbwarfarecompat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

final class LagTrace implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LAG_TRACE_PROPERTY = "dominionsword.superbwarfare.lagTrace";
    private static final String LAG_TRACE_MS_PROPERTY = "dominionsword.superbwarfare.lagTraceMs";
    private static final ThreadLocal<Deque<LagTrace>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private final String name;
    private final double warnMs;
    private final String context;
    private final long startNanos;
    private final StringBuilder marks = new StringBuilder();
    private long lastMarkNanos;

    private LagTrace(String name, double warnMs, String context) {
        this.name = name;
        this.warnMs = warnMs;
        this.context = context;
        this.startNanos = System.nanoTime();
        this.lastMarkNanos = startNanos;
    }

    static LagTrace start(String name, String context) {
        if (!enabled()) return null;
        LagTrace trace = new LagTrace(name, warnMs(), context);
        STACK.get().push(trace);
        return trace;
    }

    static void mark(String label) {
        if (!enabled()) return;
        Deque<LagTrace> stack = STACK.get();
        if (stack.isEmpty()) return;
        stack.peek().addMark(label);
    }

    private void addMark(String label) {
        long now = System.nanoTime();
        double deltaMs = (now - lastMarkNanos) / 1_000_000.0D;
        lastMarkNanos = now;
        marks.append(" | ").append(label).append('=').append(String.format(Locale.ROOT, "%.3fms", deltaMs));
    }

    @Override
    public void close() {
        if (!enabled()) return;
        Deque<LagTrace> stack = STACK.get();
        if (!stack.isEmpty() && stack.peek() == this) stack.pop();
        else stack.remove(this);
        double totalMs = (System.nanoTime() - startNanos) / 1_000_000.0D;
        if (totalMs >= warnMs) {
            LOGGER.warn("[DominionSword/SuperbWarfareLag] {} took {}ms context={}{}",
                    name, String.format(Locale.ROOT, "%.3f", totalMs), context, marks);
        }
    }

    private static boolean enabled() {
        String property = System.getProperty(LAG_TRACE_PROPERTY);
        if (property != null) return Boolean.parseBoolean(property);
        try {
            return SuperbWarfareCompatConfig.lagTraceEnabled();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static double warnMs() {
        String property = System.getProperty(LAG_TRACE_MS_PROPERTY);
        if (property != null) {
            try {
                return Double.parseDouble(property);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            return SuperbWarfareCompatConfig.lagTraceWarnMs();
        } catch (IllegalStateException ignored) {
            return 8.0D;
        }
    }
}
