# 1.11.0-beta.1 — Ground pathfinding optimization

Requires Dominion Sword 1.37.0-beta.1. Adds shared server planning admission, progressive corridor-first snapshot sampling, incremental segment validation and stale task cancellation. Ground profiles use engine metadata, excluding stationary weapons and aircraft. Local wheel candidates retain heading and minimum turn length. Route lookahead cannot cut unchecked corners or jump from a partial route to the final destination.

Based on source version 1.10.19, not the locally inspected 1.10.22 binary; the latter's additional tracked recovery changes are not fully restored here. Shared fleet corridors are experimental and enabled through the core sharedGroundRoutes server option. No live physics/performance claim is made.
