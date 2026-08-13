# iOS Design Contract

## Source and precedence

- Visual source: Figma `초보 식집사`, `Page 1`, node `0:1`.
- Local evidence: `.omo/evidence/ulw/session/G001-execute-the-approved-omo-plans-ios-a/a1/task-4-figma-page1.png`.
- Figma controls palette, hierarchy, and spacing. Apple HIG controls safe areas, system permissions, tab semantics, Dynamic Type, and accessibility.
- The 48pt radius visible on device mockups is presentation chrome and must not be applied to the app window.

## Tokens

| Role | Value |
|---|---|
| Canvas | `#FCFBF7` |
| Surface | `#FFFFFF` |
| Accent | `#3D6642` |
| Subtle surface | `#EEF3F0` |
| Corner radius | `8`, `12`, `16` pt |
| Minimum control target | `44` pt |
| Central camera action | `52` pt |

Text and border colors remain semantic tokens so contrast can be checked independently. Typography uses Dynamic Type text styles rather than fixed point sizes.

## Components and states

- Cards use a white surface, 16pt radius, subtle border, and 16pt internal spacing.
- Primary actions use the accent color and expose a semantic accessibility label.
- Four tabs are Home, Collection, Storage, and Settings. Each owns an independent navigation stack.
- The central camera action is not a fifth tab; dismissal returns to the previously selected tab and stack.
- Loading, empty, unavailable, signed-out, and content states keep the same shell geometry.
- Invalid routes fall back to Home. Deleted targets render an Unavailable destination without leaking object metadata.
- Auth-required routes are sanitized before storage and resume only after authentication succeeds.

## Motion and accessibility

- Standard state transitions use 0.2 seconds; Reduce Motion removes the animation rather than replacing it with a different motion.
- Every interactive control has a semantic label and a minimum 44pt target.
- Korean AX5 content may wrap vertically and must not clip or overlap the tab bar.
- Light mode is the Todo 4 reference appearance. System safe areas remain authoritative at every supported size.

## Reference surfaces

Snapshot and UI checks cover 402x874, 390x844, Korean AX5, light appearance, and Reduce Motion. The reference capture confirms Page 1 includes home, capture, collection, settings, room, login, and care flows; individual downstream feature fidelity is implemented by their owning Todos.
