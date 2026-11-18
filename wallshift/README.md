# WallShift: rotating wallpapers for CMF Phone 1

## Features
- Rotates your own photos, GIFs, animated WebPs and videos every 15 min to 24 h, in order or shuffled.
- Apply to Home, Lock, or Both.
- **Live wallpaper mode** (turn it on in the app once): GIFs and videos play on the home screen,
  and wallpapers wider than the screen scroll as you swipe between home screen pages.
- **In-app updates**: every GitHub build becomes a Release. The app checks for new ones and
  installs them over the current version, so wallpapers and settings are kept.

## Repo layout
```
.github/workflows/build.yml   <- builds the APK and publishes a Release
wallshift/                    <- the Android project
```

## How the screens behave
| Apply to | Live wallpaper on | Live wallpaper off |
|---|---|---|
| Home | Animated, scrolls with pages | Static (still frame for GIF/video) |
| Lock | Static image on lock | Static image on lock |
| Both | Home animated; lock mirrors it | Static on both |

Never uninstall to update. Uninstalling is the only thing that deletes your wallpapers.
