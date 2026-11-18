# kiwi-app

The Android app of the Kiwi Network: everything the control page and the kiwi-server desktop app
do, from a phone. It is a front end for the network's control node, not a second kiwi-server.

## Read first

- [kiwi-server](https://github.com/derlocke-ng/kiwi-server): `README.md`, `docs/using.md`,
  `docs/control.md` (the control page, feature by feature), `docs/design/controllers.md`
  (how a computer joins and syncs).
- The control page is the spec: `lib/kiwiserver/web.py`. The desktop app: `gui/kiwi-server-gui`.
- What each of the three offers: `notes/REVIEW-page-parity.md` in kiwi-server. The Android app
  joins that table; a gap is written there, not left silent.

## How it works

- **The phone is a controller**, like a second computer. It joins with the link or QR code from
  "Add a computer" (`POST /api/join`) and keeps its own age X25519 identity, wrapped by Android
  Keystore keys. The owner chooses what unlocks it: a password, fingerprint or face alone, the
  screen lock. To log in, it opens the challenge from `POST /api/controller/hello` and gets a
  token back.
- **It reaches the control node over the network's VPN** (WireGuard) and checks the certificate
  against the fleet's CA (`GET /api/ca`).
- **The control node does the work.** The app shows and asks: applies, updates, backups and logs
  run there as jobs.
- **The JSON API today:**
  - `GET`: `/api/status`, `/api/fleet`, `/api/fleet/revision`, `/api/activity`,
    `/api/hosts/{name}/logins`, `/api/jobs/{id}/log`;
  - `PUT`: `/api/fleet`;
  - `POST`: `/api/invite`, `/api/join`, `/api/join-me`.

  Home, Machines and Services read `GET /api/machines` (the fleet as `list --porcelain` gives
  it) and `GET /api/status`, with the controller's token. kiwi-server has no `/api/machines`
  yet and answers `/api/status` only to the page's login: until both are there, the app says
  the control node is older than it.

  Everything else the page does today by form posts (apply, settings, devices with their QR
  code, accounts, who may reach what, backups, services, module sources, game servers) needs a
  JSON endpoint in kiwi-server first. Build it there, sharing one function with the page; never
  rebuild the logic in the app.

## Look: like F-Droid 2.0

- Kotlin, Jetpack Compose, Material 3, edge to edge, dynamic color with the kiwi green as the
  fallback, dark mode.
- A bottom bar with few destinations: **Home** (how the network is, what needs a look),
  **Services**, **Machines**, **More** (devices, accounts, who may reach what, backups,
  activity, settings).
- Home in the manner of F-Droid's Discover: horizontal rows of cards, a search field at the top.
  Detail pages have a big header and the main actions right under it.
- Short texts. The owner finds long ones tiring.
- Keep the project's words: master, node, control node, machine, fleet, apply, module,
  module source. Never use friendlier synonyms.

## Build

- Gradle with the Kotlin DSL and a version catalog.
- Free software dependencies only, no Google Play services: it should build for F-Droid,
  reproducibly. Choose an age implementation only after checking its licence and upkeep.
- The development PC is Fedora Atomic: build in a container with podman, not with SDKs
  installed on the host. `container/gradlew` is `./gradlew` in that container. GitHub Actions
  runs `./gradlew assembleDebug lint test` on every pull request. Every push to main
  is a release `v0.1.<number of commits>` with the signed APK (`release.yml`).

## Order

1. A skeleton that builds in CI: theme, the bottom bar, empty screens.
2. Join with an invite link, then log in.
3. Home, Machines and Services read-only, from `/api/status` and `/api/fleet`.
4. Actions, each with its JSON endpoint added to kiwi-server first.

## Rules

- No secrets in the repository: no fleet file, keys, invite links, passwords or addresses of
  real machines. The repository is public.
- No model names in repository files.
- Never push to main. Open a pull request and merge only when CI is green.
- Test against the test network's control node, never against the live network.
