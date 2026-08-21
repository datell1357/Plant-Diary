# Planterior iOS Design System

## 1. Atmosphere and identity

Planterior is a calm, practical room for a novice plant owner. Warm off-white
canvas, quiet gray-green surfaces, image-led plant and room content, and earthy
green actions make care feel approachable rather than clinical. The signature
is the miniature room: it remains the visual anchor on Home and in editing
flows, while care status and warnings stay compact and actionable.

The visual contract is Figma `초보 식집사`, `Page 1`. The complete source
inventory is
`.omo/evidence/ulw/plant-diary-figma-ui-20260820/G001-rebuild-the-existing-planterior-ios/a1/c001-figma-contract/manifest.json`.
Figma controls hierarchy, copy, component anatomy, and visual tokens. Apple HIG
controls native safe areas, provider authentication, system permission UI,
Dynamic Type, VoiceOver, touch targets, and adaptive preferences.

## 2. Color

| Role | Swift token | Light value | Usage |
|---|---|---:|---|
| Canvas | `PlanteriorPalette.canvas` | `#FCFBF7` | App and scroll backgrounds |
| Surface | `PlanteriorPalette.surface` | `#FFFFFF` | Cards, rows, sheets |
| Subtle surface | `PlanteriorPalette.subtle` | `#EEF3F0` | Empty states and inactive nested cards |
| Accent surface | `PlanteriorPalette.accentSurface` | `#EBF0EC` | Icon wells and selected panels |
| Accent | `PlanteriorPalette.accent` | `#3D6642` | Primary actions, selection, active navigation |
| Text primary | `PlanteriorPalette.textPrimary` | `#1F2937` | Titles and body |
| Text secondary | `PlanteriorPalette.textSecondary` | `#6B7280` | Supporting copy and metadata |
| Text tertiary | `PlanteriorPalette.textTertiary` | `#9CA3AF` | Placeholders and disabled labels |
| Border | `PlanteriorPalette.border` | `#E5E7EB` | Card, row, field, and sheet boundaries |
| Warning surface | `PlanteriorPalette.warningSurface` | `#FFF7D6` | Weather and care warnings |
| Warning | `PlanteriorPalette.warning` | `#E97800` | Warning icon and emphasized copy |
| Success surface | `PlanteriorPalette.successSurface` | `#EEF5EE` | Healthy and completed status |
| Destructive | `PlanteriorPalette.destructive` | semantic system red | Deletion and irreversible actions |

Provider-owned Google and Apple screens retain their provider colors. No
provider color enters the app design system. Accent is functional emphasis,
never decoration. Add a semantic role here before adding a new visual color.

## 3. Typography

Use SF Pro through SwiftUI Dynamic Type. Fixed Figma point sizes describe
hierarchy, but shipping views use the semantic styles below so AX5 can reflow.

| Role | Swift style | Weight | Usage |
|---|---|---|---|
| Screen title | `.headline` | semibold | Navigation and modal titles |
| Large page title | `.title3` | bold | Collection, storage, shop, and login titles |
| Hero greeting | `.title3` | bold | Home greeting and room title |
| Section title | `.headline` | semibold | Care, storage, settings groups |
| Card title | `.subheadline` | semibold | Plant, item, and setting rows |
| Body | `.body` | regular | Primary content and controls |
| Supporting | `.subheadline` | regular | Descriptions and status |
| Caption | `.caption` | regular/medium | Metadata, counts, and tabs |
| Micro label | `.caption2` | medium | Compact pills only |

Titles use one or two lines. Korean particles and short predicate phrases
should not be orphaned when a slightly wider text region or a smaller semantic
style can preserve meaning. Body copy never relies on truncation for required
information.

## 4. Spacing and layout

All spacing is based on 4pt.

| Token | Value | Usage |
|---|---:|---|
| `PlanteriorSpacing.xs` | 4 | Icon-label and metadata gaps |
| `PlanteriorSpacing.sm` | 8 | Compact row and chip spacing |
| `PlanteriorSpacing.md` | 12 | Fields and dense card groups |
| `PlanteriorSpacing.lg` | 16 | Standard card padding and page gutter |
| `PlanteriorSpacing.xl` | 20 | Comfortable section separation |
| `PlanteriorSpacing.xxl` | 24 | Sheet and hero separation |
| `PlanteriorSpacing.section` | 32 | Major content groups |
| `PlanteriorSpacing.board` | 40 | Figma composite-board padding only |

The reference phone state is 402x874 with a 16pt screen gutter and 370pt
full-width content. Every phone frame has one body scroll
owner above a fixed safe-area-aware tab bar. The shell also reflows at 390x844.
Composite Figma boards use 40pt outer padding and 40pt gaps only to present
multiple 402x874 screens; those values are not in-app gutters.

The 48pt Figma phone radius and 1pt device border are presentation chrome and
must not be applied to the app window. In-app radii are 8, 12, 16, 20, 24,
and full-pill.
Safe areas, keyboard avoidance, and system bars remain native. Fixed action
regions compose as a scroll-body shell: the body owns vertical scrolling and
its safe-area inset reserves the action region inside that scroll owner
(StyleGallery `scroll-body-shell`). Responsive media uses the `frame` contract:
its aspect ratio and a meaningful minimum block size survive AX5. Every inline
navigation title uses an opaque Canvas toolbar background so body content never
renders through navigation chrome.

## 5. Components

### App shell and bottom navigation

- Structure: one independent `NavigationStack` per Home, Collection, Storage,
  and Settings tab; a center camera action occupies the fifth visual position.
- Layout: fixed safe-area bottom region; only the selected tab body scrolls.
- State: selected icon and label use Accent; unselected items use secondary or
  tertiary text; camera is a 52pt Accent circle.
- Behavior: camera dismissal returns to the previous tab and stack.
- Accessibility: each item exposes a label, selected trait, stable identifier,
  and at least a 44pt target.

### Surface card and grouped list

- Structure: Surface background, 1pt Border where needed, 16pt radius, 16pt
  internal spacing. Dense settings groups may use 12pt radius and dividers.
- Variants: standard, subtle, warning, success, selectable, disabled.
- State: pressed/selected uses a tonal Accent treatment; disabled keeps
  readable contrast.
- Depth: border and tonal shift only; no decorative shadow.

### Hero media

- Structure: image-led room, plant, or item media with 16pt radius and explicit
  aspect ratio.
- Variants: miniature room, plant hero, item hero, camera/photo review.
- State: loading uses a stable Subtle frame; unavailable uses meaningful copy,
  never a layout collapse.
- Accessibility: decorative room geometry is hidden; meaningful plants/items
  have concise labels.

### Plant and item row

- Structure: 40-56pt thumbnail, title, status/metadata, optional trailing
  action or disclosure.
- Variants: care due, completed, upcoming, unavailable, owned, eligible,
  locked, and selected.
- Layout: content wraps vertically at AX5; trailing actions never compress the
  title below a useful reading width.

### Item grid card

- Structure: image, item name, state/price metadata, optional badge.
- Layout: two columns at reference width; one readable column when Dynamic Type
  or available width requires it.
- State: acquired, eligible, locked, placed, selected, and disabled.
- Accessibility: eligibility is available as a semantic value, not color only.

### Primary and secondary actions

- Primary: Accent fill, white text, 12pt radius, full-width where the Figma
  hierarchy makes it the completion action.
- Secondary: Surface fill, Border outline, primary text.
- Minimum hit target: 44pt. Reference primary-button visual height: 52pt.
- State: normal, pressed, disabled, loading, success, and error feedback.

### Bottom sheet and modal overlay

- Structure: dimmed live surface, white bottom sheet, 24pt top corners, drag
  indicator when system presentation provides one, fixed completion action
  above the safe area.
- Variants: sign-in, room rename free, room rename paid, picker, and item
  detail.
- Behavior: focus moves into the sheet; close restores the invoking control.
- Rename copy and entitlement state are explicit. The paid state must not
  silently spend value.

### Settings row and care settings

- Structure: grouped settings cards with 44pt rows, leading semantic icon,
  title, current value or toggle, and disclosure where navigation follows.
- Icon frame: every settings icon composes `PlanteriorIconWell`; the shared
  square scales with Dynamic Type and reserves its own column. Local fixed-size
  icon wells are not permitted (StyleGallery `icon-frame`). Wrapped labels align
  from their first text baseline and cannot enter the icon column.
- Quiet hours: enable switch, start/end selectors, explanatory copy, warning,
  and fixed save action.
- Region: search, current-location action, current region, recent regions, and
  clear selection feedback.

### Capture and identification

- Camera: full black surface with close, centered photo viewport, library,
  shutter, and camera-switch controls.
- Sticky completion actions belong to the screen's `ScrollView` safe-area inset,
  never an outer wrapper, so hero, selected detail, and alternate content can
  scroll entirely above the rendered action region at every Dynamic Type size.
- Photo review: image-led confirmation with identify and retake actions.
- Identifying: same photo context with a calm leaf progress treatment.
- Result: hero photo, confidence/species summary, alternative candidates, and
  one primary registration action.
- Native camera permission and picker UI remain system-owned.

### Mini-room editor

- Structure: close/title/save header, room canvas, category tab strip,
  horizontally scrolling item selector, undo/reset footer.
- Canvas: room remains the visual focus and supports touch plus VoiceOver move
  actions. At AX5 it retains at least 180pt of visible height; the room scroll
  region grows before fixed tray/footer strips can collapse the canvas.
- State: clean, dirty, saving, error, conflict, and unsaved-dismissal recovery.

## 6. Motion and interaction

| Type | Duration | Behavior |
|---|---:|---|
| Press/toggle | 100ms | State feedback only |
| Standard | 200ms | Tab content, sheet, selection, status change |
| Identification progress | continuous | Progress conveys active analysis; no decorative loop elsewhere |

Motion explains state or spatial continuity. Use opacity and transform where a
custom transition is required. Under Reduce Motion, non-essential animations
are removed rather than replaced. Provider and system transitions remain
native.

## 7. Depth and surface strategy

The strategy is mixed tonal shift, subtle borders, and one restrained ambient
shadow family. Canvas, Subtle, Accent Surface, and Surface provide most
hierarchy. Cards may use a 1pt Border plus `y=1, blur=2, black 4%`; sheets and
dialogs may use `y=-2, blur=16, black 10%`; the camera action may use
`y=2, blur=8, Accent 25%`. Avoid additional decorative shadows, gradients,
glass effects, and nested card stacks.

## 8. Accessibility constraints and accepted debt

### Constraints

- Minimum interactive target is 44x44pt; the camera action is 52pt.
- All controls expose semantic labels, values, traits, and stable identifiers.
- Korean AX5 may expand vertically and must not clip, overlap the tab bar, or
  hide completion actions.
- Required content is not communicated by color, image, or animation alone.
- Body regions have one declared scroll owner; fixed headers and tab bars do
  not create nested vertical scrolling.
- Focus order follows visual order. Opening and closing a modal has a stable
  focus destination.
- Reduce Motion removes non-essential motion.
- Light appearance is the exact Figma reference. Additional appearances may
  use system adaptation only after the light contract remains intact.

### Accepted debt

None. Critical or Major visual, usability, CJK, or accessibility findings block
completion until fixed and reverified.

## Reference inventory

The 13 top-level frames contain 22 mobile states:

1. Home rename free
2. Home rename paid
3. Camera capture
4. Photo review
5. AI identifying
6. Identification result
7. Settings
8. Warehouse
9. Item shop
10. Item detail
11. Logged-out Home
12. Sign-in sheet
13. Authenticated Home
14. Google provider sign-in
15. Apple provider sign-in
16. Mini-room editor
17. Collection list
18. Plant detail
19. Symptom/remedy detail
20. Empty collection
21. Quiet-hours settings
22. Region settings

Provider frames are invocation contracts, not app-rendered copies. Every other
state must have a live SwiftUI owner, deterministic QA route, and fresh
Simulator capture at final review.
