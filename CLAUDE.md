# Agent Instructions

## Issue Tracking

This project uses **bd (beads)** for issue tracking.
Run `bd prime` for workflow context at the start of every session.

**Critical rules:**
- Use beads for ALL task tracking (`bd create`, `bd ready`, `bd close`)
- Do NOT use TodoWrite, TaskCreate, or markdown files for task tracking
- Create a beads issue BEFORE writing code; mark `in_progress` when starting

**Planning with beads:**
When planning multi-step work (plan mode, large features, multi-file changes):
1. Create a **top-level epic** for the overall goal
2. Create **phase epics** as children of the top-level epic for each major phase
3. Create **tasks** as children of their phase epic
4. Set **dependencies** between phases/tasks: `bd dep add <issue> <depends-on>`
5. Work through tasks in dependency order via `bd ready`

Example: "Add testing infrastructure" epic → Phase 1 "Add dependencies" epic → individual tasks.
This replaces plan-file task lists — the beads hierarchy IS the plan.

**Quick reference:**
```bash
bd ready                    # Find unblocked work
bd show <id>                # View issue details
bd update <id> --status=in_progress  # Claim work
bd close <id>               # Complete work
bd dep add <issue> <dep>    # Add dependency
bd dep tree <id>            # View dependency tree
bd dolt push                # Push beads to remote

# Creating a plan hierarchy
bd create --title="Feature X" --type=epic                        # top-level epic
bd create --title="Phase 1: Setup" --type=epic --parent=<epic>   # phase epic
bd create --title="Do the thing" --type=task --parent=<phase>    # task in phase
bd create --title="Phase 2: Build" --type=epic --parent=<epic> --deps=<phase1>  # phase depends on phase 1
```

For full workflow details: `bd prime`

## Building


## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - `bd create` for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - `bd close` finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   bd dolt push
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
