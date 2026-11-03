# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Primary: people who already use Grayjay on their phone and want their subscriptions and
sources on the living-room TV (Android TV / Google TV boxes, remote-only input). Setup leans
on the phone they already hold: a QR-code companion page served by the TV, and Grayjay sync.
TV-only newcomers are secondary; first run must still work with a remote plus the companion page.

## Product Purpose

Grayjay is a FUTO media player that aggregates many video platforms (YouTube, Nebula,
Twitch, PeerTube, Odysee, and others) through JavaScript source plugins. Subscriptions,
history and playlists live on the device, not in a central account. The TV interface lets
those users sit back and watch: browse home and subscription feeds, search, open a channel,
and play full-screen with up-next.

## Positioning

One feed across platforms, owned by the user. The comparable TV apps (YouTube, Nebula,
Peacock, Netflix, Plex, Twitch) each show one walled catalog; Grayjay's TV home mixes creators
from every installed source, and nothing is tied to an account on Grayjay's side.

## Operating Context

- 10-foot viewing, D-pad remote, often a dim room; overscan-safe margins.
- Text entry and URL entry are painful on a remote, so plugin installs, following creators
  and search typing can move to the phone through the QR companion page.
- Phone and TV may already be paired through Grayjay sync.

## Capabilities and Constraints

- TV v1 scope: Home, Subscriptions, Search, Channel, full-screen player with up next, and the
  QR companion page (install plugin by URL, follow a creator, send a search to the TV).
  Library, downloads, comments, settings and sync management stay on the phone UI for now.
- Plugins run code. The companion server starts only from the TV's "Use your phone" screen,
  stops when the TV app leaves the foreground, on "Stop sharing", or after 15 idle minutes,
  and requires a random per-session secret carried in the QR code. Every plugin install
  still needs confirmation on the TV, which shows the plugin's permissions and warnings.
- Existing Kotlin View-based app; the TV UI reuses its state, plugin and player layers.

## Brand Commitments

Grayjay name, icon and existing dark visual identity (see app resources) carry over to TV.

## Product Principles

1. The remote is the whole input device; never require typing on the TV for a core task.
2. Secure by default: the phone is a convenience, never a bypass of TV-side consent.
3. Content first: thumbnails and titles lead; chrome recedes during lean-back viewing.
4. Reuse the phone app's state and plugin layers instead of forking behavior.

## Accessibility & Inclusion

Every control reachable and visibly focused by D-pad; text sized for 10-foot reading.
