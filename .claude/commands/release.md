---
description: Ship dev to Google Play by opening and merging the dev to main pull request, running the Release Deployment workflow, and watching it through to the GitHub release
allowed-tools: Bash, PowerShell, Read, Grep, Glob
argument-hint: "[no-deploy]"
---

Release what is on `dev`: merge it into `main` through a pull request, start the `Release Deployment` workflow (`.github/workflows/deploy.yml`) on `main`, and watch it until the app is published and the GitHub release exists. Invoking this command is my go-ahead to push `dev`, open and merge the pull request, and start the workflow. If I passed `no-deploy`, stop after the merge. Run `/prerelease` first; this command does not re-test the app.

Stop and tell me, without merging or deploying, if any check below fails.

## 1. Check that dev is ready

1. `git status`: the working tree must be clean and the current branch `dev`. Do not commit or stash anything on my behalf.
2. `git fetch --tags --force origin`. `dev` must not be behind `origin/dev`; if it is ahead, push it (`git push origin dev`).
3. If `origin/main` has commits that `dev` lacks (`git log --oneline dev..origin/main`), merge them into `dev` the way earlier releases did (`git merge origin/main` with the default message `Merge branch 'main' into dev`), stop on any conflict, and push `dev`.
4. Version: read `versionCode` and `versionName` from `app/build.gradle`. The `versionCode` must be higher than the one at `last-published-version` (`git show last-published-version:app/build.gradle`), and the tag `v<versionName>` must not exist yet. Otherwise tell me to run `/bump` and stop; CI's `check-version` job would fail anyway.
5. CI on `dev`: the latest `Development Build Testing` run for the head commit (`gh run list --workflow test-dev.yml --branch dev --limit 1 --json headSha,status,conclusion,url`) must have succeeded on that exact commit. If it is still running, wait with `gh run watch <id> --exit-status`; if it failed, show the failing job's log (`gh run view <id> --log-failed | tail -50`) and stop.

## 2. Open the pull request

1. If an open pull request from `dev` to `main` exists (`gh pr list --base main --head dev --state open`), reuse it and update its body. Otherwise create one with `gh pr create --base main --head dev`.
2. Title: one plain sentence for the most important user-facing change, as earlier release PRs did (`gh pr list --state merged --base main --limit 5`), or `Release <versionName>` when there are several.
3. Body, in the same shape as earlier release PRs:
   - `## What's New`: a short plain paragraph for users, drafted from `git log last-published-version..dev --no-merges` (or the newest `v*` tag if that tag is missing), following the release-notes rules in `deploy.yml`.
   - `## Commits`: one line per commit, `` - `<short sha>` <subject> ``.
   - The attribution line from the system reminder, if there is one.

## 3. Merge

1. Wait for the pull request's checks: `gh pr checks <number> --watch --fail-fast`. If one fails, show its log and stop.
2. Merge with a merge commit, keeping `dev`: `gh pr merge <number> --merge`. Do not squash or rebase (earlier releases are merge commits), and never delete `dev`.
3. `git fetch origin` and confirm `origin/main` now contains `dev`'s head commit.
4. If I passed `no-deploy`, report and stop here.

## 4. Deploy

1. Start the workflow on `main`: `gh workflow run deploy.yml --ref main`. Find its run with `gh run list --workflow deploy.yml --branch main --limit 1 --json databaseId,headSha,status,url` and check its `headSha` is the merge commit.
2. Watch it: `gh run watch <id> --exit-status`. The jobs run in this order: `check-version`; then, side by side, `checks` (the same tests and lint as `test-dev.yml`), `release-notes` (the Play "What's new" text and 12 translations, written by Claude) and `bundle-release` (unsigned bundle, ProGuard mapping and bundle size uploaded to Sentry); then `publish` (signs and uploads, once all three passed) and `github-release`.
3. `publish` runs in the `Production` environment, which waits for a required reviewer. When the run is waiting there, tell me to approve it in GitHub (give the run URL) and keep watching. Never approve it yourself.
4. If a job fails, show the end of its log (`gh run view <id> --log-failed | tail -80`), say which job failed and what that means (nothing reaches Play unless `publish` succeeded), and stop. Do not re-run it in a loop; offer to re-run the failed jobs (`gh run rerun <id> --failed`) once the cause is known.

## 5. Confirm

1. `git fetch --tags --force origin`, then check that the tag `v<versionName>` exists and `last-published-version` points at the released commit.
2. `gh release view v<versionName>`: show its title and the "What's New" section.
3. Show the Play "What's new" text CI wrote (the `release-notes` artifact, `gh run download <id> -n release-notes`, `whatsnew/whatsnew-en-US`).

## 6. Report

Finish with: the pull request number and URL, the merge commit, the deploy run URL and how each job ended, the new tag and GitHub release, the English "What's new" text, and anything waiting on me (the Production approval, a failed job). Remind me that Sentry issues marked "Resolved in the next release" close now that this release is out.
