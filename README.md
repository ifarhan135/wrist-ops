# Wrist Ops

A standalone Wear OS app for the Galaxy Watch 6. Three things:

1. **Fall asleep → TV off.** Watches heart rate and wrist movement. When your heart
   rate settles below a threshold and your wrist has been still for a while, it
   buzzes once as a chance to cancel, then puts the Google TV / projector to sleep.
2. **Shake → talk.** Three sharp shakes within about a second opens a voice prompt.
3. **Claude on your wrist.** What you say goes to the Anthropic Messages API, and
   the reply is shown and spoken. If you ask it to turn the TV off, it does.

No phone app and no companion server. The watch talks to the TV directly over
Wi-Fi, and to Anthropic directly over the internet.

## How the TV gets turned off

The watch speaks the adb wire protocol to the TV on port 5555 and runs
`input keyevent 223` (`KEYCODE_SLEEP`). The whole protocol is implemented in
`adb/AdbClient.kt` and `adb/AdbCrypto.kt` — no libraries.

The watch generates its own RSA key on first use and keeps it, so the
"Allow debugging from this device?" prompt on the TV only needs accepting once.

## Updating without a PC

The watch updates itself. **Check for update** in the app asks GitHub for the
latest release, compares the tag (`v12` means versionCode 12) against what is
installed, downloads the APK over Wi-Fi and hands it to Android's installer. You
confirm once on the watch and the app restarts.

Two things make this work, and both have to be right *before* the first install:

1. **Stable signing.** Android refuses an update signed with a different key.
   CI generates a throwaway debug key on every run, so the repo needs these
   secrets (Settings → Secrets and variables → Actions):

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_B64` | the keystore, base64 encoded, one line |
   | `KEYSTORE_PASSWORD` | its password |
   | `KEY_ALIAS` | `wristops` |
   | `KEY_PASSWORD` | same as the store password |

   Without them the build still runs, prints a warning, and produces an APK that
   can never self-update. Switching keys later means uninstalling and losing
   settings.

2. **Install permission.** Wear OS has no Settings toggle for "install unknown
   apps", so grant it once over adb:

   ```
   adb shell appops set com.farhan.wristops REQUEST_INSTALL_PACKAGES allow
   ```

After that the PC is done with. Pushing to `main` builds, publishes a release,
and the watch picks it up next time you tap the button.

## Getting the APK

Push to `main` and GitHub Actions builds it, attaches it to a release tagged
`v<run number>`, and also uploads it as a run artifact.

## Installing on the watch

On the watch: **Settings → About watch → Software info**, tap **Build number**
seven times to get Developer options. Then **Settings → Developer options** and
turn on **ADB debugging** and **Wireless debugging**. Note the watch's IP.

From a computer on the same Wi-Fi:

```
adb connect <watch-ip>:5555
adb install -r wrist-ops-debug.apk
```

Accept the prompt on the watch when it asks.

## Setting it up

Typing an API key on a watch is miserable, so you can push the settings over adb
instead:

```
adb shell am broadcast -a com.farhan.wristops.SET_CONFIG \
  -n com.farhan.wristops/.ConfigReceiver \
  --es tv_ip 192.168.1.50 \
  --es api_key sk-ant-xxxxx
```

Other optional fields: `model`, `hr`, `still`, `shake`, `keyevent`,
`macrodroid_url`, `repo`.

## MacroDroid

The watch can also poke MacroDroid on your phone, so new automations get built by
dragging boxes instead of recompiling this. In MacroDroid add an **HTTP Server
Request** trigger, note the URL it gives you, and paste it into **MacroDroid URL**
in the app (or send it with `--es macrodroid_url ...`).

The watch then fires `?event=sleep` when it decides you are asleep, and
`?event=shake` when you shake it. MacroDroid's server is local network only, so
the phone has to be on the same Wi-Fi and awake. The call is fire-and-forget: a
missing phone never delays the TV.

MacroDroid cannot do the sensing side of any of this — its Wear OS companion only
offers a tap-to-run macro list plus watch connect/disconnect triggers, with no
access to heart rate or the watch's accelerometer.

Then open the app, grant the sensor and notification permissions, and press
**Start**. **Test TV off** checks the TV connection without waiting for sleep.

## On the TV side

The TV needs **Developer options → USB/Network debugging** turned on, and it has
to stay reachable on the network. The first time the watch connects, the TV shows
an authorization prompt — accept it, and tick "always allow".

Default key event is 223 (`KEYCODE_SLEEP`), which turns the display off and leaves
it off. Set `keyevent` to 26 (`KEYCODE_POWER`) if your device prefers a toggle.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| TV IP address | — | `192.168.1.50`, or `host:port` for a non-standard port |
| Anthropic API key | — | Sent as `x-api-key` |
| Model | `claude-sonnet-5-5` | Any model your key can reach |
| Asleep below this heart rate | 62 bpm | Upper bound for "asleep" |
| Minutes still before asleep | 10 | Wrist stillness required |
| Shake strength | 19 m/s² | Peak acceleration; gravity alone is 9.8 |

After it fires, it stays quiet for 45 minutes. If you move during the buzz window
it backs off for 5 minutes instead.

## Known costs

- The service holds a partial wake lock so the sensors keep reporting while the
  watch is dozing. Continuous heart rate plus that wake lock will shorten battery
  life noticeably. It is meant to be switched on in the evening, not left on.
- `ConfigReceiver` is exported so adb can reach it, which means any app on the
  watch could overwrite these settings. It cannot read them back out.
- The API key is in app-private SharedPreferences, unencrypted. That is fine
  against other apps, not against someone with adb access to your watch.
