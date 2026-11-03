---
version: 1
slug: "com-futo-platformplayer-tv-tvactivity-kt-5fc8a5aa"
primary_target: "app/src/main/java/com/futo/platformplayer/tv/TvActivity.kt"
related_targets: ["app/src/main/assets/tv/companion.html"]
---

Surface: Grayjay Android TV lean-back UI (TvActivity, TvPlayerActivity) plus the phone companion page (assets/tv/companion.html). Mode: Operate. Inherits Grayjay's world (black ground, Inter, Grayjay blue #2D63ED).

Audience and job: existing Grayjay phone users watching on a TV with a D-pad remote. Browse home and subscriptions across all installed sources, search, open a channel, play full screen. Typing, plugin links and following creators move to the phone through a QR companion page; plugin installs are always confirmed on the TV.

Comparables: YouTube, Nebula, Peacock, Netflix, Plex, Twitch Android TV apps.

## Direction contract

THESIS: Grayjay's TV home is one mixed row across every source, then a shelf per installed source, so the multi-platform truth is visible at a glance. It refuses the single-catalog billboard home every walled TV app ships.

OWN-WORLD: Pure black ground, #0D0D0D rail, Inter at 10-foot sizes, Grayjay blue #2D63ED for selection and the "new" dot only. Source badges carry each source's own colour on the thumbnail corner. Focused card: 1.06 scale, 3dp white ring, title brightens; unfocused metadata in #ACACAC.

STORY: The viewer sees their sources side by side, understands nothing is locked to one platform, and presses OK to watch. Anything that needs a keyboard is one QR scan away.

FIRST VIEWPORT: Left icon rail 96dp (Home, Subscriptions, Search, Use your phone) expanding with labels on focus. Content starts 64dp right of the rail at 48dp overscan: "Across your sources" row of large 16:9 cards, then a source shelf headed by its coloured source badge. Primary action: OK on the focused card.

FORM: Source shelves, position 6 on the ranked list; seed: none (unavailable). Signature move: source-coloured shelf headers plus the blue new-upload dot that stays lit until the card is focused once (raised from the gate-board challenger).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
