# Handoff: Linux-hosted automated device testing lab

## Assignment and scope

Research a reusable testing lab for Benji's projects, especially native apps and reverse-engineered service integrations. Produce a grounded feasibility report and recommended staged architecture, not an installation or production change. This is a separate session's research goal. Do not change or replace the active FoxyPhotos implementation goal or interrupt its Codex process.

Desired targets: Linux desktops, Android, and iOS. Preferred host is Linux; Docker/Compose packaging is preferred where it genuinely fits. MCP-accessible automation should let an agent inspect screens, operate apps, run repeatable tests, collect logs and, where feasible, inspect network traffic and debug behavior. Do not conflate container packaging with full OS virtualization or assume an iOS simulator on Linux is equivalent to a real iPhone.

## Why this matters: FoxyPhotos example

FoxyPhotos is an Android-first native Kotlin/Compose/Material 3 photo client at `/home/benji/projects/foxyphotos`, with portable provider/core boundaries and future iOS intent. The personal quality-of-life use case combines local media with Google Photos and eventually iCloud, folder-to-album mapping, manual storage transfers and carefully verified local cleanup. Integrations use reverse-engineered private APIs; lack of an official API is not itself a reason to reject them. Focus risk assessment on actual credential exposure, data loss, provider load and reliability.

Current implementation work is separate and ongoing: a local/Google experimental library and an iCloud-inclusive experiment, with manual bclone-compatible session import. The future iCloud authentication direction is settled: app-owned WebView login/session refresh, user signs into the genuine Apple page, resulting cookies/session material goes into encrypted credential storage. Manual session import remains fallback. We need to test the mechanics, not reopen that product decision.

Useful concrete test scenarios:

- Install the exact signed CI APK, launch, inspect UI, tap/scroll, capture screenshots and accessibility semantics. Exercise both Simple and Modern modes, ensuring experiments default off.
- Grant full or selected-media permissions, deny/revoke them, restart/rotate the app and check lifecycle behavior and gallery position.
- Seed a small deterministic fixture library with images/videos, duplicate names, duplicate content, changed files and nested/equal-leaf folder names. Exercise viewer zoom/pan/paging, video, selection and album browsing.
- Test background scheduling, pause/resume, cancellation, process death and network loss. Distinguish emulator evidence from actual GrapheneOS/physical-device behavior, Doze and OEM behavior.
- Drive imported iCloud sessions and future WebView refresh, expiry/replacement, cancelled login and 2FA. Real account credentials must only be entered through an approved secure path, never in chat, command arguments, recordings or reports.
- Inspect Google/iCloud list/download/auth requests and schema changes; distinguish network failure, unauthorized sessions and malformed provider responses without leaking secrets.
- Verify upload in run 1, download remote bytes in a later run, compare content against unchanged local bytes, then preview cleanup under a separate folder whitelist. Test deletion safety using disposable fixtures and test doubles, never personal media. Remote transformations or uncertain evidence must retain local media.
- Test updater download/verification/installer handoff and persistence across app replacement. Emulator should support signed APK installation and controlled OS installer interactions.

Host resource constraint: previous local Android builds filled disk. FoxyPhotos builds remain CI-only. Do not download SDK/system images, recreate Gradle caches, or install/start VMs as part of this research. Propose a separately provisioned lab with measured/estimated budgets and explicit approval before installation.

## Research questions

### 1. Feasibility by target

For Linux desktop guests, compare lightweight containers with virtual displays to full QEMU/KVM VMs. Include headed browser and ordinary desktop app testing, X11/Wayland implications, input/screenshot/accessibility interfaces and isolation.

For Android, compare Android Emulator/KVM with container-wrapped emulators and Android container approaches. Candidate leads, not endorsed results: Google's emulator tooling/container scripts, docker-android, ReDroid, Cuttlefish and physical-device ADB/Appium farms. Determine app/API compatibility, Google Play Services availability and licensing, ARM versus x86 ABI, host KVM/GPU requirements, nested virtualization, boot speed, snapshots, updates, rootability, storage and support status. Do not assume a containerized Android system faithfully matches stock Android or GrapheneOS.

For iOS, explicitly separate: real iOS virtualization, Xcode Simulator, physical iPhone automation, remote Mac runner, hosted device cloud, and specialist virtual-device products. Investigate what actually runs on Linux versus merely being orchestrated from Linux, what requires Apple hardware/macOS, what costs money, licensing constraints and what capabilities are missing. Candidate leads: Appium/XCUITest, remote Mac/device farms and specialist virtual iOS offerings. Give a realistic fallback if native Linux-hosted iOS is not practical. Do not label an Android skin or browser mock as iOS testing.

### 2. Agent control and MCP

Find existing maintained MCP servers/adapters for emulator/device control, ADB, Appium, desktop automation, screenshots and UI hierarchies. Verify actual tools, transports, auth, maintenance and supported targets, not repository slogans. Compare direct MCP device tools with an MCP bridge to Appium/WebDriver/ADB; note what's usable via existing agent computer-use tooling without another layer.

Required agent primitives: enumerate target, install/uninstall app, launch/stop, screenshot, UI tree/accessibility query, tap/type/swipe/back, permission controls, shell where supported, push/pull fixture files, logs, test execution, network controls and reset/snapshot lifecycle. Note unavailable primitives per platform. Prefer stable selectors over coordinate-only clicking; retain visual control for custom UI/gesture cases.

### 3. Packet inspection and debugging

Compare proxy-based HTTP inspection (e.g. mitmproxy), packet capture (tcpdump/Wireshark), host/guest networking, VPN approaches and emulator debug facilities. Explain TLS visibility limits, app trust stores/network-security config, certificate pinning, QUIC, WebViews and release-versus-debug builds. Separate supported app-owned instrumentation from research-only rooting/instrumentation (e.g. Frida). Never promise packet capture decrypts TLS by itself.

Specify what works for app-owned builds versus official provider clients. Scope interception to owner-authorized disposable sessions/devices and allowlisted flows. Do not capture unrelated household/host traffic. Propose redaction at capture time, short retention, restricted artifact storage and isolation of real-account runs. Tokens, cookies, passwords, signed media URLs and personal photo payloads must not reach git, CI logs or agent chat.

### 4. Repeatability, CI and resources

Design test jobs that consume exact CI artifacts rather than building on the lab host. Include artifact digest/source binding, versioned guest images, deterministic fixture seeding, reset after each run, exclusive device leases, bounded retries and cleanup. Consider cold start versus warm pools, snapshot drift, broken guest recovery and parallelism limits.

Estimate CPU/RAM/disk/GPU/KVM/network needs for a minimal Linux+Android lab and an expanded setup; label estimates and source measured claims. Do not assume current host hardware or free disk. Initial read-only inventory may be proposed/run only on the actual intended host, identified explicitly. Never infer KVM availability from Linux alone.

Evaluate private CI runner networking without exposing ADB, VNC, Appium or unauthenticated MCP endpoints publicly. Compare containers needing privileged mode or host devices with tighter VM boundaries. Include secrets delivery and teardown, account separation and risk of preserving credentials in snapshots.

## Deliverables

Create sibling files in this directory:

1. `automated-device-lab-research.md`: source-backed comparison by platform, feasibility, Linux-host versus external dependencies, MCP capabilities, inspection/debug limits, license/cost/maintenance, resource requirements and unknowns. Include source URLs and retrieval dates. Separate verified facts, estimates and proposals.
2. `automated-device-lab-plan.md`: recommended minimal architecture, fallback for iOS, staged proof-of-concept plan, concrete acceptance checklist and owner decisions. Include an illustrative Compose/topology sketch only where accurate; label unexecuted configurations, do not claim they work.
3. A short ranked brainstorming section: ideas below, their value, effort, risk and what evidence would validate them.

Candidate brainstorming topics: deterministic media fixture generator; screenshot/video evidence attached to failed tests; accessibility-first agent driver with visual fallback; record/replay fixtures with secret redaction; network fault injection and expired-auth simulation; disposable media deletion sandboxes; golden image reset; provider contract drift monitoring; UI regression baselines with human approval; scheduled smoke tests against dedicated accounts; physical Pixel lane for GrapheneOS-specific checks; Mac/iPhone lane controlled by the same Linux-hosted orchestrator; local reproduction bundles with only sanitized artifacts.

Acceptance: cover all three target platforms honestly; recommend at least one practical Android+Linux route and a realistic iOS route or evidenced blocker; compare concrete MCP options; provide actionable packet-inspection limits and a resource/security budget; map the architecture to at least the FoxyPhotos permissions, auth expiry, gallery and cleanup test scenarios. No working-lab claim without real execution. Research can complete with a grounded staged plan, without installing anything.

## Boundaries and workflow

Use current authoritative documentation and primary repositories. Verify active maintenance and compatibility instead of relying on remembered versions. Parallelize independent research if useful. Keep this scoped to research and planning; no paid services, purchases, credential collection, VM/image downloads, host configuration changes, deployments or live destructive provider actions. Commit document changes separately with no AI attribution. Do not edit the FoxyPhotos product roadmap or code under this research assignment. Finish with a concise recommendation and next smallest approved proof-of-concept, rather than an endless catalogue.
