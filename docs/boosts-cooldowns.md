# imperiumclient:boosts / imperiumclient:cooldowns — wire spec

The client's boosts and cooldowns HUDs are fed by two serverbound-nothing, clientbound-only
data channels. This doc is the contract both sides implement: the receivers ship in the
client (`ImperiumChannels.kt`, pinned by `ImperiumChannelsGameTest`), the senders in the
plugin (`net/imperiummc/integration/ImperiumClientDataChannels.kt` + the pure
`ImperiumClientDataProtocol` object). Written 2026-10-27 from the shipped code on both
sides — where this doc and either implementation disagree, one of them is wrong and must
be fixed before the payload grows.

## Channel mechanics (shared with hello/features)

- Channel names: `imperiumclient:boosts` and `imperiumclient:cooldowns` (clientbound only;
  the client never sends on them).
- Payload: **bare UTF-8 JSON**, one object per packet — Bukkit plugin messaging with no
  length prefix and no NBT framing (docs/dev-notes.md "Server protocol"). A codec that
  decodes NBT disconnects players; use the shared raw-UTF-8 codec
  (`ImperiumIntegration.RAW_UTF8` client-side).
- The plugin sends **only to first-party sessions** (a client that said `imperiumclient:hello`)
  that actually registered the target channel. The client, in turn, only announces and
  registers `imperiumclient:*` channels on the Romanica network (`ChannelGuard.isOwnChannel`),
  so other servers cannot fingerprint the client through them.
- Lenient parsing: unknown fields are ignored, a malformed packet is logged and dropped —
  never a disconnect.
- Session state: both registries clear on disconnect (the client resets session state on
  `ClientPlayConnectionEvents.DISCONNECT`). The server should treat a fresh join as a blank
  slate and push initial snapshots (the plugin pushes on hello and on any data-channel
  registration, deduped to one set per ~2s).
- No per-message ack. Replacement snapshots are cheap; re-send the full state on every change
  rather than inventing diff events.

## `imperiumclient:boosts`

One replace-semantics snapshot of every boost the player currently benefits from — the
plugin merges its global boosters and the player's personal ones (`BoosterService
.getActiveGlobalBoosters()` + `.getPlayerBoosters(uuid)`):

```json
{"boosts":[
  {"id":"typed-3","name":"2x Sell Multiplier","endsAtEpochMs":1769900000000},
  {"id":"typed-7","name":"1.5x Mine Speed","endsAtEpochMs":1769900600000}
]}
```

| Field | Type | Meaning |
|---|---|---|
| `boosts` | array | The full active set. **Replace semantics**: everything absent from the array is gone client-side; an empty array clears the HUD. |
| `id` | string | Stable within one booster's lifetime — the plugin sends `"typed-" + ActiveBooster.id`. Required; a packet entry without it is dropped. Refreshing a booster keeps its id (the entry is replaced in place, no HUD flicker). |
| `name` | string | Display-ready, e.g. `2x Sell Multiplier` (multiplier first, trailing `.0` trimmed — the `/booster list` wording). Required-ish: falls back to `id`. The client renders it verbatim, no reformatting. |
| `endsAtEpochMs` | number | **Absolute** wall-clock expiry in epoch millis (the plugin's `ActiveBooster.expiresAt`). Required; an entry without it is dropped. Entries already expired at parse time are dropped. |

Client mapping: each entry becomes a `BoostRegistry` entry keyed by `id`; the Boosts HUD
lists `name` plus remaining time, soonest-ending first. Client-side expiry is lazy on read,
so a missed final packet cannot leave a stale row — but the server should still push the
emptied snapshot promptly (the plugin's 1s diff tick detects expiry by snapshot-signature
change and re-sends).

Push triggers (plugin): join/hello, and whenever the set of `(id, endsAtEpochMs)` changes —
grant, revoke and expiry all land within one diff tick.

## `imperiumclient:cooldowns`

One replace-semantics snapshot of the player's active command/feature cooldowns (the
plugin's `CooldownUtil.activeCooldowns(uuid, now)`):

```json
{"cooldowns":[
  {"id":"home","name":"Home","totalMs":60000,"remainingMs":30000},
  {"id":"sell_wand","name":"Sell Wand","totalMs":10000,"remainingMs":2500}
]}
```

| Field | Type | Meaning |
|---|---|---|
| `cooldowns` | array | The full active set. **Replace semantics**, like boosts. |
| `id` | string | The plugin's action id (e.g. `home`, `sell_wand`). Required. |
| `name` | string | Display-ready title-case of the action (`Sell Wand`); falls back to `id`. Rendered verbatim. |
| `totalMs` | number | The cooldown's full length, for the HUD's draining bar. `0` when the server doesn't know it — the client then renders no fraction. Clamped at >= 0. |
| `remainingMs` | number | Milliseconds left **at send time**. Required; entries with it <= 0 are dropped. The client anchors the real deadline as `receivedAt + remainingMs` — wall clock on the client, not ticks — so seconds fall out of band-sync naturally. |

Client mapping: a `ServerCooldownsRegistry` snapshot; the cooldowns HUD merges these server
rows with the vanilla hotbar-item cooldowns (ender pearls, chorus fruit, shields). Entries
expire lazily on read; `remainingFraction` derives the bar from `remainingMs / totalMs`.

Push cadence (plugin): join/hello, then at most one snapshot per 5s while any cooldown is
active, plus **one final empty push** when the last cooldown lapses so the HUD clears
immediately instead of waiting out the lazy expiry.

## Without the server

Both features are inactive by design until the first packet arrives: the registries start
empty, the HUD entries draw nothing, and nothing registers or announces toward a server
that never speaks the channels. Malformed and hostile input is covered by gametest
(`ImperiumChannelsGameTest`: non-JSON bodies, wrong-typed arrays, junk entries — all no-ops).

## Versioning

Additive only: a sender may add new fields inside the objects (the receivers ignore
unknown keys). Renaming or re-typing an existing field is a breaking change — bump the
protocol the way hello/features does and keep the old shape working for one release.
