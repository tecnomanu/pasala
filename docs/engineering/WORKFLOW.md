<!-- guardrails-workflow:start v2.2 native-apps -->
<!-- Fixed block. Do not edit; replace it whole to upgrade. Project notes go below the end marker. -->

# Change workflow

For non-trivial changes. A typo or a one-line fix skips to step 5.

PLAN → IMPLEMENT → INDEPENDENT REVIEW → SECURITY/RISK REVIEW (when relevant)
→ TEST + RUNTIME PROOF → ARCHITECTURE CHECK (when structural) → OWNER BRIEF
→ RELEASE CHECK (when deploy-sensitive)

## 1. Plan

Do not edit yet.

- Trace the current behavior through the real execution path.
- Name the exact files and modules you will touch.
- Find the canonical existing operation, constant, pattern or component and reuse it.
- List writes and side effects (persistence, jobs, mail, events, external calls).
- Name the auth, authorization and tenant/account boundaries involved.
- State risks and how you will verify.
- Classify architecture impact: NONE / LOCAL / STRUCTURAL.

## 2. Implement

- Follow the plan; if reality contradicts it, update the plan first.
- Match repository conventions and neighboring code.
- Keep the diff scoped: no unrelated refactors or cleanup.
- Add or update tests; a bug fix gets a regression test when feasible.
- No casual dependencies; justify any new one.
- Preserve logs, metrics and error reporting; never log secrets or personal data.
- Comments: 1–2 lines of *why*. Longer reasons go to the project's long-why
  file (`HISTORY.md` or an existing equivalent) or `DECISIONS.md`,
  and the comment points to the anchor.
- Review your own final diff before calling it done.

## 3. Independent review

Fresh context when possible. Self-review does not count.

- Requirement vs implementation.
- Duplicated business logic or a second source of truth.
- Wrong auth/authorization assumptions, tenant or account leaks.
- Tests that mirror implementation instead of proving behavior.
- Unnecessary abstractions.
- Narrative comments, personal data, local paths or AI-tool metadata in the diff.
- The stack checks below.

Classify each finding BLOCKER / SHOULD FIX / OPTIONAL, with concrete evidence.
Do not return generic checklist noise.

## 4. Security / risk review

Only the attack surfaces the change touches: auth vs authorization, isolation,
input validation, injection (SQL, HTML, command, prompt), SSRF, redirects,
uploads, webhooks, secrets, rate limits, payments, dependency risk, plus the
stack checks below.

## 5. Test + runtime proof

Use the real commands from `TESTING.md`. Report each level separately:

1. builds / parses / typechecks
2. static checks and lint
3. unit and feature tests (which, how many passed)
4. the real path exercised (browser, process, worker, device) with evidence
5. integrations touched (queue, realtime, external API, payments)

Anything not verified is written as `UNVERIFIED`. A green build is not proof
that the feature works.

## 6. Architecture check

Compare against `AGENTS.md`, `GUARDRAILS_PROFILE.md`, `SYSTEM_MAP.md` and the
neighboring code. Look for a second implementation of one operation, a wrong
layer, new global state, hidden side effects, boundary drift and docs that
became false. Do not refactor for aesthetic purity.

## 7. Owner brief

For a technical owner who will not read the whole diff:

- **What changed**
- **Flow:** entry → operation → persistence/side effect → response
- **Files worth knowing:** at most 7
- **Risk:** LOW / MEDIUM / HIGH
- **Proof:** exact checks and runtime verification done
- **Architecture:** NONE / LOCAL / STRUCTURAL
- **If it breaks:** where to look first, how to roll back
- **Human attention:** at most 3 things worth checking personally

## 8. Release check

Only for deploy-sensitive work. Review what applies: schema/data migrations,
environment variables, build output, workers and schedulers, caches, auth/IdP
config, webhooks, external services, feature flags, observability, rollback
sequence. Return GO / GO WITH CAUTION / NO-GO. Never GO on unit tests alone.

When something breaks in production: find the failing boundary, collect
evidence first, rank hypotheses, apply the safest mitigation, and fix
permanently only after the cause is proven.

## Stack checks — Native apps (Swift / Kotlin)

- **Plan:** name the platform touched (Apple, Android or both) and the process that runs the code (app, extension, background service, helper binary). When apps share a wire protocol or file format, the contract document changes first and every side changes in the same change: new fields optional, breaking changes bump the version.
- **Implement:** go through the repo's single owner of state (engine, store, repository, ViewModel) instead of adding a second writer; no disk, network or database I/O on the main actor or main dispatcher; stream large files and `content://` URIs instead of loading them whole; Android writes shared media through MediaStore (pending, then published) or the Storage Access Framework, never raw paths; permissions, foreground service types, entitlements and `Info.plist` usage strings land in the same change as the code that needs them.
- **Review:** main-thread blocking and state crossing actors or dispatchers unsafely; lifecycle assumptions (process death, configuration change, app in background, service killed or not restarted); exported components and intent filters wider than needed; deep links and share intents used without validation; a second source of truth beside the canonical store; stored-data changes without an additive migration.
- **Security:** anything from a deep link, share intent, IPC, local server or peer is untrusted (file names, paths, MIME types, sizes); writes stay inside the app's or the destination folder; secrets in Keychain/Keystore or app-private storage, never in plists, exported prefs or logs; cleartext and ATS exceptions justified; only the entitlements and permissions the feature uses.
- **Runtime proof:** the build running is the one just made (Swift Package `swift build`, Xcode scheme or `xcodebuild`, the packaged `.app`, `adb install -r`; one instance only). Exercise the changed path on a target: `adb shell am start` for deep links and share intents, `adb logcat` as evidence, both sides of a protocol. Say whether it ran on an emulator/simulator or a real device; the emulator does not prove camera, background limits, battery restrictions, Wi-Fi/multicast/discovery or notifications. Ad-hoc signed macOS builds can trigger privacy prompts (Local Network, camera, files) again after every rebuild.
- **Release:** signing identity, provisioning, hardened runtime, notarization and entitlements (Apple); keystore, `versionCode`, target SDK and store policy for permissions and foreground service types (Android); minimum OS versions; compatibility of stored data and of the wire protocol with versions already installed.

<!-- guardrails-workflow:end -->

## Specifics of this project

<!-- Only what differs from the fixed block above, per step. Link to TESTING.md, SYSTEM_MAP.md and CONVENTIONS instead of repeating them. Keep it short. -->

- **Plan:** the contract is [`docs/PROTOCOL.md`](../PROTOCOL.md); the two apps share no code. Name the side (Mac, Android or both). [SYSTEM_MAP.md](SYSTEM_MAP.md), [DECISIONS.md](DECISIONS.md).
- **Implement:** Mac behavior goes through `Engine` (single owner of state); Android through `core/` over `data/Store`. `pasala-mcp` only reads the database; anything that sends or pairs goes through the app's `/local/*`. `Engine` is `@MainActor`, so every `db.*` call runs on the main thread: keep new queries small or move them off it. UI text follows `AGENTS.md` rule 5. Long whys go to [HISTORY.md](HISTORY.md) with an anchor.
- **Review:** received names go through `uniqueURL` (Mac, `PasalaCore/Net.swift`) and `Receiver.sanitize` (Android); a new write path uses them too. A `pasala://pair` link never pairs on its own: it opens the confirmation dialog. Scan the diff for personal data (names, real IPs, device serials, local paths, emails) before committing; this repo is public.
- **Security:** every `/v1/*` route authenticates the peer before reading the body; `/v1/pair` is the only exception and depends on the one-time code; `/local/*` is loopback + token; MCP tools with side effects go through the app.
- **Test / runtime proof:** run the changed path against the other side (real device, `mac/tools/smoke.py` or `android/tools/fake_peer.py`). [TESTING.md](TESTING.md), "Camino real" table.
- **Release:** no store or CI yet. A protocol change needs both apps updated on the owner's devices; schema changes are additive migrations in `PasalaDB.migrate()`.
