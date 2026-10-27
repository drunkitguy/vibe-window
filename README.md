# vibe-window

Second-screen companion for The Legend of Zelda: Tears of the Kingdom on dual-screen handhelds, as an [Eden Duo](https://github.com/igawa6/eden-duo) `.dsmod.zip` package.

- Plan and status: [docs/PLAN.md](docs/PLAN.md)
- Agents (`.claude/agents/`): `manager` runs the loop, `planner` researches and plans, `coder` builds, `critic` reviews and loops the coder until fully satisfied.

No game data, keys or firmware live in this repo. Everything game-derived is read from the player's own files at runtime.
