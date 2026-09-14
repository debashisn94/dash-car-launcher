# Testing checklist

Everything from v1.1 and v1.2 compiles but has **never run on the panel**. This is the list to
work through at the car, roughly in this order, because later items depend on earlier ones.

Install first. Note that v1.1 changed the signing key, so the currently installed debug build
must be **uninstalled** rather than upgraded:

```bash
adb uninstall com.debashis.carlauncher
adb install -r app/build/outputs/apk/release/app-release.apk

adb shell pm grant com.debashis.carlauncher android.permission.ACCESS_FINE_LOCATION
adb shell cmd notification allow_listener \
  com.debashis.carlauncher/com.debashis.carlauncher.DashNotificationListener
```

Settings reset to defaults after the uninstall. That is expected.

---

## 1. Settings screen

- [ ] **Long press anywhere on the dock** opens Settings. This is the only entry point.
- [ ] Left nav switches sections and the selected item stays highlighted.
- [ ] **Done** closes and returns to the home screen.

### Dock
- [ ] All eight slots listed with the right icons and names.
- [ ] Tapping a slot opens the picker; picking an app updates the row **and** the home dock.
- [ ] Picking **Leave empty** blanks the slot, and the home dock keeps its spacing rather than
      shuffling the other tiles sideways.
- [ ] Picking **All apps** makes that slot open the drawer.
- [ ] A slot whose package is gone reads **"Not installed on this unit"** in amber.
- [ ] **Reset dock to defaults** restores the original eight.

⚠️ The picker selects at **component** level. Check that picking CarPlay gives
`com.imotor.phoneconnect/...Carplay` and not the package default, which was the original bug.

### Wallpaper
- [ ] **Choose an image** opens the system picker and the chosen photo appears on the home screen.
- [ ] It survives a **reboot**. This is the one that matters: the file is copied rather than
      holding a Uri, precisely because that permission does not survive one.
- [ ] **Remove wallpaper** falls back to the colour wash.

### Display
- [ ] Clock: Follow the unit / 12 hour / 24 hour all take effect on return.
- [ ] Units: switching to mph changes both the number and the label.

---

## 2. Drive mode  (needs actual driving)

- [ ] Below 30 km/h the normal eight-tile screen shows.
- [ ] **Above 30 km/h** it switches: four large tiles, speed dominant, media card larger.
- [ ] Dropping below 18 km/h returns to normal.
- [ ] **Stop-start traffic does not flicker** between the two. This is the whole reason there
      are two thresholds instead of one.
- [ ] The three tiles launch correctly, and **All apps** is the fourth.
- [ ] Music playing before the switch is still shown correctly after it, with the right
      play/pause state. Both layouts are fed by the same render path, but that is untested.
- [ ] Settings -> Driving -> Off keeps the normal screen at any speed.

---

## 3. Dimming

- [ ] **Idle:** leave the home screen untouched for 3 minutes. It should dim.
- [ ] Any touch restores it immediately, and that same touch should **not** also trigger a tile.
      If it does, that needs fixing.
- [ ] Open Netflix or CarPlay and leave it for 5 minutes: it must **not** dim, because the
      timer only runs while the home screen is showing.
- [ ] **Night:** between 19:00 and 06:00 the screen sits dimmer. Changing the level in Settings
      is visible immediately.
- [ ] Night and idle together should give one dim at the deeper level, not two stacked.

---

## 4. Screen off

- [ ] **Long press the clock** blanks the screen to black with a small clock.
- [ ] Tapping anywhere wakes it.
- [ ] It does not survive leaving and returning to the home screen.

---

## 5. Regressions to re-check

Things that worked before and could have been broken by the dock now coming from preferences:

- [ ] All eight tiles launch the right app.
- [ ] Drawer still pages sideways with a **short flick**, not just a full drag.
- [ ] Drawer icons appear on the page you are on, including after flicking to page 2 and back.
      Icons are now loaded per page and dropped elsewhere, which is new and untested.
- [ ] Now playing still shows music, and still shows the **radio** with no metadata.
- [ ] Speed reads 0 when parked, not 1.
- [ ] No old vendor wallpaper flash on launch.
- [ ] **Reverse camera** still works. Nothing here should touch it, which is exactly why it is
      worth confirming once.

---

## Known unknowns

- Drive mode has never been seen at speed. The threshold values are a guess at what feels right.
- Idle dimming interacts with `onUserInteraction`, which fires for every touch. If the wake tap
  also activates whatever is under it, that is a design decision to revisit, not a crash.
- The vendor's floating assist button still overlaps the speed digit in the top right corner.
