# Buteo on Google Play

How Buteo gets to Google Play (D24, D26), in the order it happens.
The Play Console moves its menus around: its search box (top) finds
each page named here.

Status (2026-11-21): the app exists in the Play Console; the internal
test is next.

## 1. Now: the internal test

### Choose the app signing key first

Play re-signs what it installs. A new app gets a key Google makes for
it, unless you give Play Buteo's own key (`~/.android/buteo-release.jks`,
D24). Decide before the first upload; it can change only until a
release reaches open testing or production.

**Recommended: Buteo's own key.** Then Play, GitHub and Obtainium
install the same app: a pilot can move from one to the other and
updates follow. With a Google-made key they are two apps with the same
name that cannot replace each other: switching means uninstalling,
which deletes `XCSoarData` (flights, maps, profile). Your own phone,
with the GitHub build, is the first case.

How:
1. Open **Play app signing** for Buteo, **Change key** (or "Choose
   signing key" on the first release), and pick providing a copy of
   your own key from a Java keystore.
2. Download the PEPK tool and Google's encryption key the page offers,
   and run the command it shows, with
   `--keystore=$HOME/.android/buteo-release.jks --alias=buteo`. It
   looks like:
   ```sh
   java -jar pepk.jar --keystore=$HOME/.android/buteo-release.jks \
     --alias=buteo --output=buteo-key.zip --include-cert \
     --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
   ```
   It asks for the keystore and key passwords
   (`~/.android/buteo-release.properties`).
3. Upload `buteo-key.zip`, then delete it.

The same key stays the upload key: the release workflow's bundles are
signed with it, so nothing else changes. (Google advises a separate
upload key; it protects little here, as the app key itself must stay on
your machine to sign the GitHub APKs.)

### The bundle

Upload v1.0.1's bundle or later, not v1.0.0's: v1.0.0 claims 32-bit and
x86 phones, where Buteo cannot start (fixed by `aa991098a3`).

- From a release: tag `v1.0.1` (with `mobile/docs/releases/v1.0.1.md`);
  the workflow run (GitHub → Actions → mobile-release) has the artifact
  `buteo-1.0.1-play-bundle` (a zip with the `.aab`) for 90 days.
- Or built here, signed (as in the host build notes):
  `./gradlew -PbuteoVersionName=1.0.1 -PbuteoVersionCode=10001 -Pxcsoar.abis=arm64-v8a :app:bundleRelease`
  → `mobile/app/build/outputs/bundle/release/app-release.aab`. Use the
  version of a real tag: Play refuses a version code twice.

### The release

1. **Internal testing** → **Create new release**.
2. Upload the `.aab`. Warnings about missing native debug symbols and a
   deobfuscation file can be ignored (no R8, symbols not packaged yet).
3. Release name `1.0.1`; notes: a few lines (500 characters at most).
4. **Next** → **Save and publish** (or Start rollout). Internal tests
   are not reviewed; for up to 48 hours the app may show a temporary
   name.

### Testers

1. **Internal testing** → **Testers**: create an email list with the
   Google accounts of the testers (yours first; up to 100), save.
2. Copy the release's link and open it on each tester's phone, signed in
   with that account: accept, then install from Play.

Internal testing does not count towards production access (§3).

## 2. Each release after the first: automatic upload

With a service account, a tag's workflow also uploads its bundle to
the internal track as a **draft** (D26): you roll it out, then promote
it, in the Play Console. Without the secret, the workflow skips it.

Once:
1. In the [Google Cloud console](https://console.cloud.google.com/),
   a project (any), **APIs & Services** → enable **Google Play Android
   Developer API**.
2. **IAM & Admin** → **Service accounts** → create one (no roles), then
   **Keys** → **Add key** → JSON: a file downloads.
3. Play Console → **Users and permissions** → **Invite new users**: the
   service account's email; **App permissions** → Buteo → **Release apps
   to testing tracks** (and View app information). Not production.
4. The secret, then delete the file:
   ```sh
   gh secret set PLAY_SERVICE_ACCOUNT_JSON -R CorentinGrard/buteo < key.json
   ```

Each release: `mobile/docs/releases/<tag>.md` (GitHub), optionally
`mobile/docs/releases/<tag>.play.txt` (Play's "What's new", 500
characters at most; else a link to the GitHub release), then the tag.
The draft appears under **Internal testing**: **Edit release** →
**Save and publish**.

## 3. Production: a closed test first

A personal developer account created after 13 November 2023 must run a
**closed test with at least 12 testers opted in for 14 days in a row**
before it can ask for production access
([Play's rule](https://support.google.com/googleplay/android-developer/answer/14151465)).
Club pilots are natural testers; they must stay opted in all 14 days.

A closed test needs the store listing and the App content declarations
below; Google reviews it. Then: promote the release to **Closed
testing**, add the testers' list, and after 14 days **Apply for
production** (a questionnaire about the test). Then production, best
with a staged rollout.

### App content (Policy → App content)

| Declaration | Answer |
|---|---|
| Privacy policy | https://github.com/CorentinGrard/buteo/blob/mobile/mobile/docs/PRIVACY.md |
| App access | All functionality is available without special access |
| Ads | No ads |
| Advertising ID | Not used (no `AD_ID` permission) |
| Content rating | Category utility/other; every answer no except "shares the user's location with other users": yes (live tracking) → Everyone / PEGI 3 |
| Target audience | 18 and over (not designed for children) |
| News app, Health, Financial features, Government app | No / none |
| Foreground service | Type **Location**, see below |
| Data safety | See below |

**Foreground service (Location).** Tasks: **Navigation** and
**User-initiated location sharing** (live tracking), not "Background
location updates" (no background location permission). Text: "Buteo is a glide computer for glider pilots. While it
runs, a foreground service with a visible notification keeps the GPS,
the glide calculations, airspace warnings and the flight log (IGC)
going when the screen is off or another app is in front: a pilot
cannot keep the screen on for a whole flight. It starts when the pilot
opens Buteo." Video (YouTube, unlisted, the same link for both
tasks): one captioned screen recording of 1:41 on a Pixel 7. In it,
the pilot picks an airfield (Go to waypoint) and Buteo navigates there,
turns on Live tracking, puts another app in front, and comes back
through the notification: the distance and the trail kept updating.
The phone's GPS provider was fed (an adb test provider) with a glide of
`test/data/01lz1hq1.igc` moved to Burgundy, the app's barometer was
idled, and its network was blocked, so that no fake position reached a
tracking server. No background location permission: Buteo does
not request `ACCESS_BACKGROUND_LOCATION`, so no background location
declaration.

**Data safety.** Buteo itself collects nothing, but optional features
send data off the phone to services the pilot chooses (PRIVACY.md);
Play counts that. A draft to check:

- Does the app collect or share data: **Yes**.
- Encrypted in transit: **No** (SkyLines and XCSoar Cloud tracking are
  UDP, the LiveTrack24 login is plain HTTP).
- Users can request deletion: **No** (the developer holds no data;
  uninstalling deletes what is on the phone).
- Data types, each **optional**, purpose **App functionality**,
  collected (live tracking, NOTAMs, WeGlide), not shared: Play does
  not count a transfer the pilot starts and expects (turning tracking
  on, tapping Upload) as sharing. Processed ephemerally: **No** (the
  services keep the tracks and the uploaded flight):
  - Location: **Precise location** (and approximate).
  - Personal info: **Name** (in the flight log, LiveTrack24 user name),
    **User IDs** (WeGlide ID, SkyLines key), **Other info** (date of
    birth for WeGlide).
  - Files and docs: the flight log uploaded to WeGlide.

### Store listing

- App name: `Buteo – glide computer` (30 characters at most)
- Short description (80 at most): `Glide computer for glider pilots,
  built on XCSoar: map, final glide, airspace.`
- Full description: the README's "What it does", with its two warnings
  (not made by the XCSoar project; a backup instrument, not
  certified), and a link to the source
  (https://github.com/CorentinGrard/buteo, GPL).
- Category: Maps & Navigation. Contact email: required, your choice.
  Website: the GitHub page.
- Graphics: icon `mobile/app/src/main/ic_launcher-playstore.png`
  (512 px); a feature graphic (1024 × 500) and two phone screenshots or
  more are still to make (a replay of `test/data/01lz1hq1.igc`, copied
  into `XCSoarData/logs`, shows the app away from your area).
