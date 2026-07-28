# Preferences

Window size and position, the model you last worked on, and whether the log panel was collapsed
are kept in `preferences.json` beside the database cache and the backups:

```
~/Library/Application Support/EpsonReset/preferences.json     macOS
%APPDATA%/EpsonReset/preferences.json                         Windows
~/.local/share/epson-reset/preferences.json                   Linux
```

Hand-editable, like `network-printers.txt` and `counters-overlay.json`. Every field has a default
and is read on its own, so a bad value costs that preference rather than the launch, and a file
that isn't JSON at all just starts the app as if it were the first time.

A remembered window position that no longer lands on a screen is dropped and the window manager
places the window instead — an unplugged second monitor should not open the app somewhere you
can't reach it. A remembered model yields to whatever the connected printer says it is; it fills
the selection when nothing else has, and never over the top of the printer's own answer.

## Update check

The app asks GitHub for its latest release once a day and offers a link if there is a newer one.
It never downloads or installs anything — the most it does is open the release page in your
browser. Builds that aren't releases are exempt: `./gradlew run` stamps `dev` rather than a version
number, and a version that can't be compared never counts as older — so a working copy is not told
to upgrade to the release it is ahead of. **Check for updates** in the top bar asks immediately.

To turn the automatic check off, set it in `preferences.json`:

```json
{ "checkForUpdates": false }
```

The button still works; nothing is contacted until you press it.
