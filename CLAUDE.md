# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

`yt_kids` is an app for watching YouTube videos for kids. It wraps YouTube
playback in a restricted, parent-curated interface: children only see videos
an adult has explicitly approved, with no search, comments, recommendations,
or autoplay into unrelated content.

See `README.md` for the product goals.

## Repository state

The repository is new — no source code, build system, or dependencies exist
yet. Once a stack is chosen, document these here:

- Build, run, and test commands
- Project layout and where the main entry points live
- How YouTube playback is embedded (IFrame Player API, Data API, etc.)
- Where the curated video list and parent settings are stored

## Conventions

- Keep the child-facing surface free of anything that leads outside the
  curated content — that constraint is the point of the app, not a nice to
  have.
- Keep parent-only functionality behind an explicit gate, separate from the
  kid-facing views.
- Never commit API keys or OAuth secrets; load them from environment
  variables or an untracked local config file.
