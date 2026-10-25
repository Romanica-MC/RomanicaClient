# Product

## Register

product

## Users
Romanica players: grinders, PvP players, story players and cosmetic collectors on a Rome-themed prison
server (play.romanica.net). They open the client's menus mid-session, often between mining runs or fights,
to toggle a module, move a HUD element or change a setting, and want to be back in the game within seconds.
Many play for hours, so the UI has to stay readable on a dimmed game world at any GUI scale.

## Product Purpose
Romanica Client is the server's own Fabric client mod: a complete PvP and quality-of-life feature set (HUD
modules, zoom, waypoints, chat tools, performance options) with a fast module menu and HUD editor, plus
server integration (server-gated features, cosmetics, legions). Success: players pick it over other
clients because it is quicker, cleaner and feels like part of the server, and new players find every
setting without asking in Discord.

## Business Model
The client and launcher are free, with every feature included, for good (owner decision, 2026-10-25). They
are how players find and stay with the Romanica server; anyone who wants to support the project does it by
spending on the server's store. No ads, no sponsored panels or partner links, no "upgrade" prompts, no
locked features. Cosmetics are sold only when they belong to the server (rank capes, event rewards earned
or bought through the Romanica store) and are shown, never pushed: no pop-ups, banners or store screens
that interrupt play.

## Brand Personality
Clean, confident, imperial. The menus follow the reference layout in `docs/design/README.md` (owner decision, 2026-10-19):
its layout, sizing, motion and polish, carrying the server's emblem, name and bronze palette. Copy is
plain British English; Latin appears only in the brand mark and the motto "Roma Aeterna", never as filler.

## Anti-references
- Low-effort AI output: muddy textures, visual noise, low-contrast grey-on-grey text, generic stock art.
- Generic "dark SaaS dashboard with a gold accent": flat cards with no hierarchy and no game behind them.
- Neon gamer clients (RGB edges, aggressive gradients and glows everywhere).
- Cartoon fantasy RPG UI (parchment scrolls, bubbly ornaments).
- Mismatched screens: vanilla grey buttons or old config screens next to the new shell.
- Ad-supported client UI: banner ads, sponsored tiles, cosmetic shop pop-ups, "buy" badges on menus.

## Design Principles
1. **Reference structure, our identity.** Follow the reference layout and proportions faithfully: the title screen,
   the sidebar shell, module cards, setting rows, toasts and HUD editor. Change only the brand (emblem,
   wordmark), the palette (bronze on warm dark) and the icons.
2. **The game stays visible.** Translucent overlay controls with a light blur over the real world or
   panorama, never a flat opaque wallpaper.
3. **Minecraft-native details.** Vanilla item icons in module tiles, the Minecraft font for HUD text and
   in-world numbers, the vanilla panorama (later a server-branded one) behind the title screen.
4. **Back to the game fast.** Search first, one click to toggle, settings one level deep.
5. **One theme system.** Every colour is a token with the names listed in `docs/design/README.md`, so themes can
   be swapped without touching layout.

## Accessibility & Inclusion
- Text meets WCAG AA contrast (4.5:1 body, 3:1 large) against the menu surface, including labels and
  placeholders.
- Works at GUI scales 1–4 and with the vanilla font option.
- "Reduce motion" removes all animation.
- State is never shown by colour alone: enabled cards change border and the power icon, server-blocked cards show a block icon and a reason line.
