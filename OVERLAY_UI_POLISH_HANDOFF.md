# Overlay UI Polish Handoff

Use this handoff to resume the Astra overlay UI polish phase without re-auditing the whole repo.

## Current status

Repo: `EpicIsTheOne/Astra`
Android app root: `astra-android/`

The overlay work is now in three layers:

1. **Launcher / permission layer**
   - `AstraOverlayController.kt`
   - Handles `Settings.canDrawOverlays(...)`, overlay permission intent, and starting/stopping the overlay service.

2. **Real overlay service**
   - `AstraOverlayService.kt`
   - Owns the floating orb and the in-service floating panel.
   - Uses `WindowManager` + `TYPE_APPLICATION_OVERLAY`.
   - Runs as a foreground service.
   - Current manifest declaration includes `foregroundServiceType="specialUse|microphone"` with special-use subtype `floating_overlay_assistant_panel`.

3. **Floating panel controller**
   - `AstraOverlayPanelController.kt`
   - This now owns the live overlay panel behavior directly:
     - typed input
     - speech recognition attempts
     - live OpenClaw chat
     - TTS replies
     - transcript persistence
     - status/waveform/orb state
     - auto-listen on open

There is also still an older activity implementation:
- `AstraOverlayActivity.kt`

That activity is now partly redundant. It still exists as a useful fallback/debug path and for permission-adjacent flows, but the real product direction is the service-owned panel.

## Recent relevant commits

Overlay architecture / fixes:
- `d31976c` — make Astra panel directly launchable for testing
- `a5eb1b6` — add real system overlay beta service skeleton
- `7d79886` — add expandable in-service overlay panel beta
- `1d7abfc` — move live chat loop into floating overlay panel
- `287cde7` — Android 14 overlay FGS type crash fix
- `4b8d85b` — outside-app voice capture via microphone FGS type
- `9fdd358` — auto-start listening when floating panel opens

Updater stability:
- `071816f` — shut down Astra services before in-place install

## Files that matter for overlay polish

### Primary overlay files
- `astra-android/app/src/main/java/com/astra/wakeup/ui/AstraOverlayController.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/AstraOverlayService.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/AstraOverlayPanelController.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/AstraPanelLauncher.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/AstraQuickSettingsTileService.kt`
- `astra-android/app/src/main/java/com/astra/wakeup/ui/AstraOverlayActivity.kt`

### Layout / visuals
- `astra-android/app/src/main/res/layout/activity_astra_overlay.xml`
- `astra-android/app/src/main/res/drawable/bg_astra_panel.xml`
- `astra-android/app/src/main/res/drawable/bg_astra_surface.xml`
- `astra-android/app/src/main/res/drawable/bg_astra_input.xml`
- `astra-android/app/src/main/res/drawable/bg_astra_orb.xml`
- `astra-android/app/src/main/res/values/themes.xml`

### Manifest / permissions
- `astra-android/app/src/main/AndroidManifest.xml`

## Current behavior

### What works
- Overlay permission path exists.
- Floating orb appears and can be dragged.
- Tapping orb opens an in-service floating panel.
- Floating panel can:
  - auto-listen on open
  - typed send
  - get OpenClaw replies
  - speak replies
  - persist transcript history
- Outside-app listening now has microphone foreground-service support.
- QS tile works as a summon path.
- In-app launch path works.

### Known product/UX gaps
This is where polish work should focus.

1. **Gemini-like visual refinement is not done**
   - layout still looks like a capable beta, not a finished premium assistant surface
   - spacing, typography, hierarchy, and motion need refinement

2. **Panel sizing/positioning is still basic**
   - currently bottom-aligned full-width-ish overlay panel
   - no resize affordance
   - no snap states
   - no partial-sheet/compact mode

3. **Orb behavior is minimal**
   - draggable but not edge-snapped
   - no magnetism / docking
   - no saved position persistence yet
   - no fancy idle/active morphing beyond basic styling

4. **Activity/controller duplication still exists**
   - `AstraOverlayActivity.kt` and `AstraOverlayPanelController.kt` overlap conceptually
   - long-term, shared logic should be extracted instead of duplicating behavior forever

5. **Mic permission path is still imperfect**
   - overlay panel can listen if permission already exists
   - if not, fallback still depends on launching app/activity path
   - acceptable for beta, not ideal polished UX

6. **No overlay-specific settings panel yet**
   - no dedicated toggles for:
     - start collapsed vs expanded
     - auto-listen on open
     - orb size
     - orb side / saved position
     - persistent overlay enable on boot

## Recommended next UI polish priorities

### Phase 1: visual polish
Goal: make the overlay feel closer to Gemini while still being Astra.

1. Refine `activity_astra_overlay.xml`
   - cleaner top section / tighter title block
   - better spacing rhythm
   - stronger transcript bubble styling
   - improved status chip hierarchy
   - less chunky buttons

2. Improve motion
   - panel expand animation
   - collapse-to-orb transition
   - subtler waveform/orb animation states

3. Make orb feel premium
   - edge snap
   - better glow/shadow
   - maybe active listening/speaking rings

### Phase 2: interaction polish
1. Save orb position in prefs
2. Add compact state / half-sheet state
3. Add smarter keyboard behavior for typed input
4. Improve auto-scroll and transcript presentation
5. Add explicit mute/interrupt affordance

### Phase 3: architecture cleanup
1. Extract shared overlay chat logic from:
   - `AstraOverlayActivity.kt`
   - `AstraOverlayPanelController.kt`
2. Keep the service panel as primary
3. Reduce the activity to fallback/debug/permission path only

## Important implementation notes

### Foreground service requirements
Do not casually remove these; Android 14 will throw a fit:
- manifest permission: `FOREGROUND_SERVICE_SPECIAL_USE`
- manifest permission: `FOREGROUND_SERVICE_MICROPHONE`
- service type: `specialUse|microphone`
- special-use subtype property on `AstraOverlayService`
- explicit `startForeground(..., typeFlags)` path in `AstraOverlayService`

### Overlay specifics
The real overlay path is `WindowManager` + `TYPE_APPLICATION_OVERLAY`.
That is the official Android direction being used here.

### Update/install caveat
`ApkUpdateInstaller.kt` now shuts down Astra services before in-place install.
If future changes add more sticky/foreground services, update that cleanup path too or in-place updates may become cursed again.

## Suggested first changes when resuming
If picking this back up later, start here:

1. Read:
   - `AstraOverlayService.kt`
   - `AstraOverlayPanelController.kt`
   - `activity_astra_overlay.xml`

2. Do a focused polish pass on:
   - header
   - transcript area
   - input row
   - orb visuals

3. Only after that, decide whether to:
   - unify logic with `AstraOverlayActivity.kt`
   - add position persistence
   - add compact/collapsed panel states

## Goal for the next phase
Make Astra’s overlay feel like:
- fast
- alive
- premium
- less clunky than the current beta
- closer to Gemini’s immediacy
- but still unmistakably Astra, not some generic minimal assistant clone

## Overlay safety validation checklist (run on-device before shipping)
This is the do-not-brick-the-phone checklist. If any of these fail, do not ship more overlay polish pretending it is fine.

1. **Open path sanity**
   - Launch from orb, app, and QS tile if available.
   - Confirm the panel opens as a bottom sheet footprint only.

2. **Outside-touch safety**
   - Confirm the area outside the visible panel is fully transparent.
   - Tap and scroll another app outside the panel.
   - Confirm the rest of the phone remains interactive and never feels covered by an invisible fullscreen layer.

3. **Dismiss reliability**
   - Dismiss using the drag handle repeatedly.
   - Test light drag, long drag, and rapid reopen/dismiss cycles.
   - Confirm dismissal always removes the sheet and returns control cleanly.

4. **No trapped-state regression**
   - With keyboard open, with speech listening active, and after TTS playback, verify the overlay still dismisses.
   - Confirm there is always a working path back to normal phone interaction.

5. **Reply-surface correctness**
   - On first open with no Astra reply yet, confirm the latest-reply area is hidden.
   - After Astra replies, confirm the latest-reply area appears.
   - For long replies, confirm collapsed-by-default behavior and tap-to-expand still work.

6. **Visual safety constraints**
   - Outside-panel background must remain transparent.
   - Do not reintroduce fullscreen dim, fullscreen root height, or touch-blocking blanket behavior unless there is a very deliberate and tested interaction model.

7. **Release gate**
   - If the phone can get "stuck behind the overlay" even once during testing, treat it as a release blocker.
