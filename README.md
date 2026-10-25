# Romanica Client

A Fabric client mod for Minecraft 26.3, made for the [Romanica](https://romanica.net) server but
usable anywhere. It's based on [AxolotlClient](https://codeberg.org/AxolotlClient/AxolotlClient-mod)
and adds its own module menu, HUD options and server integration on top.

Still early, expect things to move around.

## Features

- Module menu on Right Shift with categories, search and per-module settings
- Movable, scalable HUD elements: FPS, CPS, TPS, ping, coords, compass, armor, potions, keystrokes,
  scoreboard, boss bar and more
- Per-element styling: background, border, rounding, padding, brackets, text colour, hide in F3, layering
- Zoom, freelook, motion blur, fullbright, custom block outlines, hit colour, TNT timer,
  scrollable tooltips, Discord RPC
- Config profiles

## Building

You need JDK 25.

```bash
./gradlew :latest:build
```

The jar ends up in `versions/latest/build/libs/`. To run a dev client:

```bash
./gradlew :latest:runClient
```

There's also a client gametest that opens the menu in a test world and saves screenshots:

```bash
./gradlew :latest:runClientGameTest
```

## Contributing

Issues and PRs are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md). Plans and design notes live in
[docs/](docs/).

## Credits and license

Built on AxolotlClient by moehreag and contributors. Like upstream, this project is licensed under the
LGPL-3.0, see [LICENSE](LICENSE).

Romanica names, logos and artwork are not covered by the LGPL and belong to the project owner; forks must
use their own branding.

Romanica Client is not an official Minecraft product and is not approved by or associated with Mojang or
Microsoft.
