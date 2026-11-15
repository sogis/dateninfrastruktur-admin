---
name: simplify-change
description: Find unnecessary complexity in a proposed design, specification, or implemented change.
---

Identify required behavior, actual consumers, and the boundaries that isolate
dependencies or protect user data. For a proposal, compare the design with the
simplest direct implementation that satisfies those constraints. For a diff,
inspect the changed code and its callers to identify what can be removed.

Look for speculative extension points, trivial delegation, single-purpose
factories, duplicate models and checks, obsolete compatibility paths, and custom
build, test, or verification machinery. Prefer deletion, consolidation, and
existing APIs or standard tools. Preserve product behavior, meaningful
regression tests, and safety across actual trust boundaries.

For each proposal name the removable component, its current consumer, the
simpler implementation, and the trade-off. Prioritize changes with concrete
maintenance benefits. Report findings without editing when acting as reviewer.
