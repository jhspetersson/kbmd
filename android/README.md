# kbmd for Android

The same app as the web version, on the phone and without a server: the React UI from `../frontend` runs in a
WebView, and a port of the Spring Boot backend runs inside the app, answering the same `/api` on `127.0.0.1`.
Notes stay plain files; a vault synced with the desktop app keeps working on both.

## Build and install

```powershell
.\build.ps1            # UI + release APK  ->  android\kbmd.apk
.\build.ps1 -Debug     # debug build (WebView inspectable from chrome://inspect)
.\build.ps1 -SkipUi    # reuse the UI built last time
```

Needs Node 22+, a JDK 17–21 (one from `%USERPROFILE%\.jdks` is picked automatically) and the Android SDK
(`sdk.dir` in `local.properties`, or `ANDROID_HOME`). Gradle and the SDK platform are downloaded on first use.

Install with `adb install -r kbmd.apk`, or copy the APK to the phone and open it (allow "install unknown apps" for
the file manager). Android 8+.

The release build is signed with `keystore\kbmd-release.jks` (passwords in `keystore\signing.properties`). Both are
git-ignored. **Keep a backup**: updates can only be installed over an app signed with the same key. Without the
folder the release APK is unsigned; use `-Debug` then.

## Sync

Same dialog and settings as the web app, but no Git on the phone: sync talks to the **GitHub / GitLab REST API**
with the access token (GitHub: fine-grained token with *Contents: Read and write*; GitLab: token with the `api`
scope, since the repository API is used rather than Git over HTTPS).

- Every file is compared three ways by its Git blob id: phone, remote branch, and the state after the last sync.
- One-sided changes are copied across as a single commit; files deleted remotely go to the vault's `.trash`.
- Edited on both sides: the phone's version stays, the remote one is saved next to it as
  `name (remote conflict <date>).md`, and both are pushed. No conflict markers ever end up in notes.
- `.flashcards.json` is synced, `.trash/` and `.kbmd/` are not (the same `.gitignore` the web app writes).
- Works with github.com, gitlab.com and self-hosted servers (`https://host/group/repo`).
- Auto sync: every N minutes while the app is open, and once when it goes to the background.
- The token and the sync state live in the app's private storage, not in the vault. Files over 50 MB are skipped.

## What the phone adds

- Phone layout: bottom bar, side panels as overlays, editor above preview in split mode; back gesture closes panels
  and dialogs. In landscape, on a tablet or in DeX the desktop layout is used.
- **S Pen**: the *New drawing* launcher shortcut opens a blank Excalidraw canvas with the pen tool selected; pressure
  is used for stroke width, and once a pen is seen fingers only pan and zoom. Handwriting-to-text in the editor comes
  from the Samsung keyboard.
- **Share to kbmd** from any app: text and links become a note in `Inbox/`, images and files become attachments
  embedded in that note. *Save to kbmd* also appears in the text selection menu.
- Launcher shortcuts (long-press the icon): new drawing, new note, tasks, daily note, habits, search.
- Attach button / upload: system file picker, with the **camera** offered beside it.
- **Calendar**: the Calendar tab can show the phone's own calendars next to the vault's events (a *Phone calendar*
  switch; it asks for the calendar permission once), every vault event has *Add to phone calendar* (opens the
  calendar app's editor with the event and its recurrence filled in), and the `.ics` export saves through the
  system dialog.
- Exports (vault zip, Anki deck) go through the system "save as" dialog; other attachments open in their apps.
- System bars follow the app's light/dark theme; edge-to-edge with the keyboard and the camera cutout respected.

The vault is in `Android/data/dev.kbmd.android/files/vault` on the shared storage (reachable over USB). Uninstalling
the app deletes it: set up sync, or export the zip first.

## Differences from the web app

- Search is a built-in token index instead of Lucene (same query syntax: prefix words, `"phrases"`, `tag:`, `path:`).
- Sync uses the provider API instead of JGit (see above), so there is no `.git` folder in the vault.
- The local server only answers requests carrying the session cookie the app sets, so other apps cannot read notes.

## Development

```powershell
.\gradlew testDebugUnitTest                      # sync engine + API over real HTTP, on the desktop JVM
$env:KBMD_DEV_SERVER = 8790; .\gradlew testDebugUnitTest --tests *DevServerTest   # the Android backend + UI in a desktop browser
```

```
app/src/main/java/dev/kbmd/android/
  MainActivity, KbmdApp   WebView shell, intents, pickers, insets
  server/                 NanoHTTPD routes = the web app's REST API
  vault/ index/ markdown/ flashcards/   ports of the Spring services
  sync/                   three-way sync engine, GitHub and GitLab clients
```
