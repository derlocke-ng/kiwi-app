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
- **At home the control node answers at its LAN address** (the LAN's DNS gives it). From
  Android 17 on an app is kept off the local network until the owner allows it
  (`ACCESS_LOCAL_NETWORK`, shown as "Nearby devices"): the app asks before it joins or logs in.
- **The app finds the control node's name itself where the phone does not**: on some phones
  the resolver answers apps with a blank address for a name of the network while a browser
  finds it. `Lookup` in `core` then asks the DNS servers of the phone's networks directly. The
  connection is checked against the fleet's CA and the name whatever the address.
- **The control node does the work.** The app shows and asks: applies, updates, backups and logs
  run there as jobs.
- **The JSON API today:**
  - `GET`: `/api/status`, `/api/fleet`, `/api/fleet/revision`, `/api/activity`,
    `/api/hosts/{name}/logins`, `/api/jobs/{id}/log`;
  - `PUT`: `/api/fleet`;
  - `POST`: `/api/invite`, `/api/join`, `/api/join-me`.

  The app reads, with the controller's token: `GET /api/machines` (the fleet as
  `list --porcelain` gives it) and `GET /api/status` for Home, Machines and Services, and
  `GET /api/activity` for Activity (kiwi-server 2.190.0 and newer). A machine's page asks
  `GET /api/hosts/{name}/logins` for its logins and passwords (2.194.0 and newer; an older
  control node answers that only to the page's login, and the app says it is older than it).

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
- Without a fleet the app starts with two ways in: **Use a network** and **Run a network**.
- Home in the manner of F-Droid's Discover: horizontal rows of cards, a search field at the top.
  Detail pages have a big header and the main actions right under it.
- Short texts. The owner finds long ones tiring.
- Keep the project's words: master, node, control node, machine, fleet, apply, module,
  module source. Never use friendlier synonyms.

## The part for the people who use the network

The app has two parts. The admin's is a controller. The other is for the people who use the
network: a user is no controller, has no key and never asks the control node's API.

- **It reads the network's registry**: `GET https://status.<control node>/api/registry`, which
  asks for no login (kiwi-server 2.193.0 and newer; the fields are in its `docs/control.md`
  under "More computers"): the services with what they are for and whether they work, the
  game servers, the sign-up page, the game page, the status page.
- **A name is enough to find it**: a machine's name or its address on the LAN answers
  `/.well-known/kiwi` with a redirect to the registry. Only that lead is taken from it. The
  CA is taken from the status name on first use (`/api/ca`); everything after is checked
  against it.
- **Pages open inside the app** (`Web` in `ui`, a web view), the admin's services too: the
  phone does not know the fleet's CA, so the app checks a page's certificate against it and
  the name itself, and only for names of the network. Any other page goes to the browser.
- **Kept apart**: `Registry.kt` in `core` and `ui/people` in `app` know nothing of
  `Controller`, `Phone` or `ControlApi`'s token. Keep it so.
- **Not there yet**: finding the network by asking the LAN's addresses, opening a service's
  own app where one is installed (the Disroot app does), registration other than the
  network's sign-up page, files a page offers to keep.
- **On the admin's phone** the same view is under More, "For the people": the registry read
  from the status name beside the control page's, with the fleet's CA and without the token.

## Build

- Gradle with the Kotlin DSL and a version catalog.
- Free software dependencies only, no Google Play services: it should build for F-Droid,
  reproducibly. Choose an age implementation only after checking its licence and upkeep.
- No phone is at hand where the app is built. `container/gradlew :app:testDebugUnitTest -Ppictures`
  draws the screens with a made-up fleet into `app/build/pictures` (`PicturesTest`): look at
  them after changing a screen, and add a picture for a new one.
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
