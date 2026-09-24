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
