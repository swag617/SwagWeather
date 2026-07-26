# SwagWeather Hardening Audit

Audited against `SwagDev-Hardening-Audit-Prompt.md` (5-pattern reusable audit), 2026-07-26.

**Executive summary:** P1: 0 hits (no persistence). P2: 2 checked, both safe (hard depend). P3: 2 fixed. P4: N/A (no bStats). P5: 0 migrated / 1 logged as future work, 1 flagged for human review.

---

## Pattern 1 — Scheduler calls reachable from shutdown (DATA LOSS class)

**Hits found:** 0.

SwagWeather holds no per-world/player state on disk — the forecast queues and season
state (`WeatherManager.states`, `SeasonManager.states`) are purely in-memory and are
expected to regenerate on next boot. There is no save-on-disable path at all.

`onDisable()` (`SwagWeather.java:76`) calls:
- `WeatherWebModule.disable()` → `webService.unregisterModule(plugin)` (SwagAPI call, no local scheduling)
- `WeatherManager.shutdown()` → `task.cancel()` only, synchronous
- `SeasonManager.shutdown()` → `task.cancel()` only, synchronous

No `getScheduler().runTask*`, executor, or `CompletableFuture` usage is reachable from
any of these paths. **Classification: not applicable to this repo.** (Note: what
`webService.unregisterModule` does internally is SwagAPI's own code, out of scope for
this repo's audit.)

**Fix:** none needed.

---

## Pattern 2 — Plugin-presence checks at enable time (WRONG-BEHAVIOR class)

**Hits found:** 2.

1. `SwagWeather.hookSwagAPI()` (`SwagWeather.java:94-114`) — looks up
   `IEventBusService` and `IWebService` via `ServicesManager` during `onEnable()`.
2. `WeatherWebModule.enable()` (`WeatherWebModule.java:34-52`) — looks up
   `IWebService` during `onEnable()`.

**Classification: safe**, not the SwagHub bug pattern. SwagAPI is declared as a hard
`depend: [SwagAPI]` in `plugin.yml`, so the server's plugin loader guarantees SwagAPI is
fully enabled (and has registered its services) before SwagWeather's `onEnable()` runs.
This is different from the SwagHub incident, where the checked plugin had no `depend`
relationship and enable order was unguaranteed. Boot-time result already equals
post-reload result here.

Both call sites additionally fail gracefully (severe+disable for the hard-required
event bus; warn+degrade for the optional web service) rather than crashing, which is
correct either way.

**Fix:** none needed. Flag for human awareness: this safety argument depends on SwagAPI
registering `IWebService`/`IEventBusService` synchronously within its own `onEnable()`
rather than via a delayed task — worth confirming against SwagAPI's own audit pass.

---

## Pattern 3 — Per-world/per-item loops without isolation (MODULE-DEATH class)

**Hits found:** 3, all fixed (1 was outside strict pattern scope, fixed on follow-up — see below).

1. `WeatherManager.tick()` (`WeatherManager.java:128-143`) — loops `Bukkit.getWorlds()`
   and calls `world.setStorm()` / `setThundering()` / `setWeatherDuration()` /
   `setThunderDuration()` with no per-world isolation.
2. `SeasonManager.tick()` (`SeasonManager.java:89-102`) — loops `Bukkit.getWorlds()`
   and reads `world.getFullTime()` with no per-world isolation.

Both are repeating tasks (`runTaskTimer`), not one-shot enable loops, but the failure
mode is the same shape as the live incident: if any single world throws (custom
dimension, a weather-incapable world type, or another future Paper API-drift case),
the exception escapes the `for` loop and every world ordered after the bad one is
skipped **for that tick** — and since it's the same bad world every time, this
effectively starves every other world's weather/season processing on every future tick
until an admin intervenes. No crash, no log line pinpointing the cause — same class of
silent, broad damage as the live incident.

**Fix:** wrapped the per-world body of both `tick()` methods in try/catch; a failing
world logs one warn line naming the world and the exception message, and the loop
continues to the next world. No behavior change for healthy worlds.

**Verify:** `mvn package` clean after the change (see Final Checks). Manual poison-test
(inject a world that throws from `setStorm`) not performed — no local test harness for
world mocks in this repo; recommend a live-server check per the "Verify" step in the
source pattern doc before the next server restart.

**Related, also fixed:** `WeatherWebHttpHandler.buildStateJson()`
(`WeatherWebHttpHandler.java:168-196`) had the identical shape — loops
`Bukkit.getWorlds()` building the admin panel's JSON with no per-world isolation, so one
bad world would 500 the entire panel instead of just omitting that world. This sits
outside Pattern 3's stated search scope (enable/reload paths only, not request-handling
paths), but was fixed on explicit follow-up request since it's the same bug shape: one
throwing world now logs a warn line naming the world and is omitted from the panel
response, the rest of the worlds still render.

---

## Pattern 4 — bStats placeholder IDs (HYGIENE class)

**Hits found:** 0. No bStats dependency, import, or initialization exists anywhere in
this repo (`pom.xml` has no bStats artifact; no `org.bstats` references in source).
**Classification: not applicable.**

**Fix:** none needed. (Not scoped to add bStats where none exists — that would be a
feature addition, not a hardening fix.)

---

## Pattern 5 — Modern-Paper API drift (FUTURE-CRASH class)

**Hits found:** 2.

1. **`org.bukkit.ChatColor` usage** — used throughout `SwagWeatherCommand.java` (every
   `sender.sendMessage(ChatColor.X + ...)` call, ~20 sites) instead of Adventure
   `Component`/`net.kyori.adventure`. `ChatColor` is legacy but still present and
   functional in the current Paper API — not removed, not runtime-hazardous, and not
   producing wrong output on modern clients. Per the audit's rule ("deprecated-but-
   working calls get logged as future work, not churned now"), **not migrated**.
   Logged here as future work: migrate to Adventure `Component`/`MiniMessage` for
   consistency with newer SwagDev plugins, at a time when the whole command's message
   layer can be reworked, not as a one-off touch.
2. No other known-shifted surfaces found: no `setTime`/`setFullTime` **setter** calls
   (only `getFullTime()`, a getter — safe, not part of the world-clock setter bug); no
   `sendTitle`, no `PlayerDeathEvent` message methods, no `AsyncPlayerChatEvent`, no
   NMS/CraftBukkit/`net.minecraft` references anywhere in the repo.

**Flagged for human review (not changed):** `pom.xml` pins
`io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT`. The audit prompt asks to compile
against "the current Paper API version the network runs (26.1.x)" — I could not verify
a `26.1.x` Paper API Maven coordinate exists, and blindly changing the pinned version
risks breaking the build against a nonexistent/wrong artifact. Per rule 6 (ambiguous →
flag, don't guess), leaving this as-is; a human should confirm the correct target
coordinate for the live network version and update it deliberately.

**Fix:** none applied this pass.

---

## Final Checks

- `mvn package` (offline): **clean**, zero warnings/errors, after the Pattern 3 fix.
- Jar load on a live Paper 26.1.x test server: **not performed** — no test server
  available in this environment; recommend a human verify per Pattern 3's live-poison
  test before the next restart.
- Commits: one per pattern where a code or doc change was made (see git log:
  `Hardening P3: ...`; P1/P2/P4 required no code change and are captured only in this
  report; P5 required no code change but the pom.xml version and ChatColor items are
  flagged above).
