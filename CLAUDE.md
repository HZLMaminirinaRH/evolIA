# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

evolIA is a **greenfield project**. The repository currently contains only `README.md` plus the initial commit — there is no source code, build system, test suite, dependency manifest, or CI configuration yet.

Per the README, the goal is to **enhance mobile digital interactions**. The technology stack, architecture, and tooling have not been chosen or committed.

## Important consequences

Because nothing has been built yet:

- There are **no build, lint, test, or run commands** to document. Do not invent or guess them.
- There is **no architecture** to describe. Read the actual files before claiming any structure exists.
- There are no Cursor rules, Copilot instructions, or other convention files in the repo.

## When real code lands, update this file

This document is intentionally sparse and should be expanded as the project takes shape. Add a section here the moment any of the following appear, so future sessions can be productive immediately:

- A dependency manifest (e.g. `package.json`, `pubspec.yaml`, `requirements.txt`, `go.mod`) — record the package manager and the install/build/test/run commands.
- A test setup — record how to run the full suite and a single test.
- A linter/formatter config — record the commands and any non-default rules.
- More than a couple of source directories — describe the high-level, cross-file architecture (the "big picture" that isn't obvious from a single file).

Keep entries grounded in what is actually in the repo; verify by reading files rather than assuming.
