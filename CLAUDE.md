# CLAUDE.md - Courtbit-API

## Git Branching Rules

**CRITICAL: Never push or merge directly to `main`.**

- Always create a feature branch from `develop` (e.g., `feature/my-feature`)
- All work happens on feature branches
- Merge via Pull Request to `develop` only
- `develop` → `main` merges are done manually for production releases

Before any git operation, verify you are NOT on `main`:
```bash
git branch --show-current  # Must NOT be "main"
```

If on `main`, switch to develop first:
```bash
git checkout develop
git pull origin develop
git checkout -b feature/my-feature
```

## Supabase Rules

**Always use the `develop` branch for all Supabase operations.**

| Branch | Project Ref | Usage |
|--------|-------------|-------|
| `develop` | `mvaqafmlgnakssijolge` | **DEFAULT** - Use this for all operations |
| `main` | `qhtykipawsulyejtvvkm` | **PROTECTED** - Only when user explicitly asks for production |

When executing any Supabase MCP tool, always use:
```
project_id: mvaqafmlgnakssijolge
```

## Common Commands

```bash
./gradlew build          # Build everything
./gradlew test           # Run tests
./gradlew run            # Run server (localhost:8080)
./gradlew buildFatJar    # Build executable JAR
```
