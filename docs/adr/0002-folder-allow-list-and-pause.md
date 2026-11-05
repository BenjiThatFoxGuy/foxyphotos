# ADR 0002: Back up only allow-listed device folders

- **Status:** Accepted
- **Date:** 2026-11-05

## Context

The initial prototype must be a useful direct Google Photos backup application without assuming every photo and video on the device belongs in its backup scope.

## Decision

Continuous backup uses a user-managed allow-list of MediaStore relative paths. The default scope is empty. The app will show eligible folders with their item counts and let the user enable or disable each one, mirroring the familiar folder-selection behaviour of Google Photos.

`Pause backups` is a first-class explicit control. Pausing prevents new transfer attempts while preserving discovered media, hashes, task history, and queued work. It must be immediately visible in the backup UI before any later automatic scheduling or routing mechanisms.

## Consequences

The database policy stores allow-listed relative paths and a paused flag. A media item outside the allow-list is not enqueued for continuous backup. Removing a folder from the list stops future tasks from being created; it does not erase confirmed history.
