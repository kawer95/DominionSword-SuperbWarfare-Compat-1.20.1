# 1.11.0-beta.6 — Explicit M2 motion calibration

Requires core 1.37.0-beta.6 for the isolated vehicle-motion session API. Adds an opt-in operator-only `/dominion_m2_test` entry for short forward/reverse movement and stationary pivots. Existing commander/driver permissions still apply. Default configuration is off; ordinary pathfinding is not switched to V2.

Native input direction and track brake semantics were checked against the deployed 0.8.9.1 jar. The controller parameters are initial estimates, not measured physics. Single-vehicle ownership, stale orders, driver changes, terrain checks, energy/wreck/ground gates and a 400-tick deadline constrain the experiment. See the core engineering `vehicle-navigation-v2/M2_CALIBRATION.md` instructions. No real-world calibration is claimed.

# 1.11.0-beta.5 — Bounded delivery and heading-aware route checks

Requires Dominion Sword 1.37.0-beta.5. Searches can deliver a validated advancing prefix after 40 server ticks, and this planning attempt ends by its 100-tick check with a usable partial or cooldown. The shared soft budget is unchanged. Final validation may use additional budget slices.

The new testable frontier preserves heading, validates tracked turns before translations, admits a checked exact-XY terminal connector, and uses terrain-aware heuristic/cost units. Route lookahead and tracked execution keep the same pivot-space check. Intermediate waypoint consumption uses the same 3D reach rule as braking.

Tests include the real Bradley dimensions and observed east wall: a less constrained goal completes; the original tight wall-side goal must not be falsely reported complete. This remains a discrete straight/pivot model, not a complete continuous vehicle motion solver. No new in-game arrival or TPS benchmark is claimed. Keep groundPathTrace enabled for the next real-world check.

# 1.11.0-beta.4.3 — Bradley route execution correction

Requires core 1.37.0-beta.4. Bradley beta4.2 logs repeatedly showed NO_PATH after only three expansions, and valid partial routes were replaced by direct final approach. Navigation collision clearance now preserves the physical bottom plane instead of expanding into the supporting floor. Active routes take precedence over direct shortcuts, failed searches back off for the same goal, and close tracked turns consider steering infeasibility.

Includes beta4.1/beta4.2 fixes: compare route displacement against the active segment, and separate one-block route-node arrival from vehicle body width. Ground path diagnostics can be disabled using debug.groundPathTrace in the compatibility config. The actual in-game arrival result still needs verification; these fixes do not constitute a complete heading-aware vehicle search.

# 1.11.0-beta.4 — On-demand ground frontier

Requires Dominion Sword 1.37.0-beta.4. Ground routes grow an on-demand height-aware frontier instead of pre-sampling a rectangle. Long targets retain their real XYZ goal and skip whole-route prechecks; checked partial paths continue from the current pose. Candidate occupancy and travel sweeps keep native vehicle geometry and limits. Explicit direct_only retains its existing configured behavior.

Search admission is bounded; the shared time budget remains soft. No game-world physics or load benchmark has been performed.

# 1.11.0-beta.3 — Remaining march route snapshots

Requires Dominion Sword 1.37.0-beta.3. `marchRoute` returns the vehicle's current position and remaining, unconsumed real path nodes. Pending/failed paths remain empty; reaching the end of a partial path allows the existing incremental planner to continue. Debug straight-line previews are not used as movement routes. Native driving/physics is unchanged in this increment.

# 1.11.0-beta.2 — Unified march ownership

Requires Dominion Sword 1.37.0-beta.2. Ground driving yields to the core persistent column/free task's waiting and conservative cruise-cap decisions. Native movement, collision and special flight/jump controllers remain addon-owned. No live physics or load benchmark has been performed.

# 1.11.0-beta.1 — Ground pathfinding optimization

Requires Dominion Sword 1.37.0-beta.1. Adds shared server planning admission, progressive corridor-first snapshot sampling, incremental segment validation and stale task cancellation. Ground profiles use engine metadata, excluding stationary weapons and aircraft. Local wheel candidates retain heading and minimum turn length. Route lookahead cannot cut unchecked corners or jump from a partial route to the final destination.

Based on source version 1.10.19, not the locally inspected 1.10.22 binary; the latter's additional tracked recovery changes are not fully restored here. Shared fleet corridors are experimental and enabled through the core sharedGroundRoutes server option. No live physics/performance claim is made.
