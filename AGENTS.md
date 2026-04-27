# Agent notes

- Do not exceed 1800px in any dimension when capturing or generating images (screenshots, previews, diffs). Downscale before returning or attaching them.
- Use `agent/...` for branch names, never `claude/...` or `copilot/...`
  (enforced by `.github/workflows/no-ai-coauthors.yml`).
- Strip any `Co-authored-by` trailers, AI bot emails, and "Generated with"
  lines from commits and PR bodies before pushing.
- Keep PR titles in conventional-commits form
  (`feat:`, `fix:`, `chore:`, `build:`, `ci:`, `docs:`, …); enforced
  by `.github/workflows/pr-title.yml` and consumed by release-please.
