# WallShift: rotating wallpapers for CMF Phone 1

Pick images from your gallery. WallShift changes the wallpaper on a timer and applies it to the
**home screen**, the **lock screen**, or **both**.

## Build (GitHub Actions)
1. Create a new GitHub repo and push this folder to it.
2. Pushing starts the **Build APK** workflow. You can also start it from Actions → Build APK → Run workflow.
3. When the run finishes, download the **WallShift-apk** artifact, unzip it, and copy the APK to your phone.
4. Install it. Allow "Install unknown apps" for your file manager when Android asks.

Every build is signed with the same key (`app/wallshift.keystore`), so a new APK installs over the
old one and keeps your wallpapers and settings.

## Use
- **Add**: pick one or more images. The app copies them into its own storage, so deleting the
  originals from your gallery won't break the rotation.
- **Change wallpaper automatically**: turns the timer on or off.
- **Change every**: 15 min to 24 h. Android doesn't allow periodic jobs more often than every 15 minutes.
- **Apply to**: Home / Lock / Both.
- **Order**: in order, or shuffle (never repeats the same image twice in a row).
- **Change now**: applies the next wallpaper right away.
- Tap a thumbnail to set it now or remove it.
- **Allow background running**: tap this once. Nothing OS limits background apps, and without
  the exemption changes can come late.

Images are scaled and center-cropped to the screen size (1080×2400 on CMF Phone 1).
