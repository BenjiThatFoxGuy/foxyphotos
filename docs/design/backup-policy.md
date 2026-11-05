# Backup policy design

Status: brainstorming only. Do not implement scheduling or overrides as part of the initial gallery increment.

## Agreed mode and condition semantics

- Modern mode is intended to back up selected folders automatically. Simple mode remains explicitly manual.
- Wi-Fi and cellular permissions are alternative eligible network paths. Roaming additionally requires cellular permission.
- Metered Wi-Fi is potentially costly and is not automatically treated as unrestricted Wi-Fi.
- Data Saver, power saver and charging requirements are independent policy gates. Charging does not implicitly bypass power saver.
- Explicit user pause outranks automatic scheduling. Network and power changes never resume a user-paused backup.
- Surface all relevant blocked reasons rather than labeling condition waits as a user pause.
- Manual runs can offer explicit one-run condition overrides. Cellular and roaming use must not be silently authorized.
- Switching Modern to Simple disables future automatic runs but allows the current run to finish.

## Requested additions, details not yet agreed

- A time-bounded immediate-upload mode: react more promptly to newly saved media from the selected folders rather than relying only on periodic checks. This is intended behavior, not a guarantee of Android scheduling latency and not a verified claim about Apple's implementation.
- Temporary condition overrides with an explicit expiry time, or a session scope that lapses when the pending batch concludes.
- Design the interaction, duration presets, overlap, failure handling and session boundaries before implementation. Do not make temporary exceptions permanent settings.

## Proposed interaction for discussion

- Keep urgency and permission separate. Immediate watching does not implicitly authorize cellular, roaming or other restricted conditions.
- Offer an Upload immediately action with a bounded duration and a Stop action. Proposed presets: 15 minutes, 1 hour, 4 hours. New media remains subject to selected-folder filters.
- Offer blocked-condition actions that explicitly list which restrictions would be bypassed. Proposed scope choices: current pending batch, 15 minutes, 1 hour, 4 hours.
- Proposed session semantics: freeze the eligible pending batch when the override is confirmed; newly arriving media does not extend that session. End the exception when that batch finishes or is cancelled. Retryable failures must not keep an exception alive indefinitely.
- On expiry, re-evaluate normal conditions and stop admitting new work under the expired exception; finish or interrupt the current transfer according to safe provider cancellation boundaries. Never represent expiration as a successful upload.
- Explicit pause or cancel must not be undone by an active temporary exception. Proposed default: expiry clocks continue during a pause; resume requires explicit user action and a fresh eligibility check.
- Show remaining time or batch scope, exact exceptions, and an End override action in backup status. No ticking countdown that fills the main gallery with explanatory text.

These proposed details are not yet accepted. No iOS implementation, OS battery exemption, persistent aggressive watcher, or override scheduler is authorized by this design note alone.
