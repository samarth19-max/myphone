# Callrec Phone

A plain, private phone app for Android that **records your calls**. It replaces the dialer, shows who is calling, and saves each call as an audio file on your phone. No account, no ads, no tracking, no cloud: your calls, contacts and recordings never leave the device.
This is a Public build version for the app I made for my current employers. 

It is also the reference app for [**callrec**](https://github.com/Youbal-oops/callrec), the small open-source library that does the recording.

**Runs on Android 15 (API 35) and above; built and targeted at API 37 (Android 17).** Recordings are encrypted at rest with AES-256-GCM, and everything — including decrypting a recording before you share it — works completely offline.

> **Not yet verified on many handsets.** Call-audio capture depends on the phone maker. Please report what works and what does not.

## What you get

- **Recents, dialpad and contacts**, with search that ignores case, accents and punctuation, and finds a number however it is written.
- **A full call screen**: answer, decline, silence the ringer, mute, speaker or Bluetooth, hold, keypad, end.
- **Saved names from everywhere**: Google, phone storage and SIM contacts are merged, and each name shows where it is saved.
- **A note per number**, shown on the call screen when that person calls or you call them.
- **Automatic call recording** once a call connects. Play, share, save an unencrypted copy, or delete from the Recordings tab. Old recordings can delete themselves after 7, 30 or 90 days.
- **AES-256-GCM encryption** of recordings at rest, with the option to store new recordings as plain audio instead.
- **Sharing always decrypts first** — send a recording to WhatsApp (or anything else) and the recipient gets an ordinary `.m4a`, never ciphertext.
- **Save on the phone without encryption** — one tap writes a plain `.m4a` into `Music/Callrec Phone`; Settings can also save *all* recordings unencrypted.
- **An offline recovery key** for restoring access to encrypted recordings if the device keystore is ever reset.
- **A built-in setup guide** (Settings → Setup) with a tick for each step, and a "how it works" list.
- **Problem reports stay on the phone.** If something breaks, Settings shows the error with a Copy button.

## Setup

The same steps are in the app under **Settings → Setup**, with a tick for each one that is done.

1. **Allow restricted settings, then the permissions.** Phone Settings → Apps → Callrec Phone → ⋮ (top right) → **Allow restricted settings** (needed for apps installed from an APK). Then tap **Allow permissions** in the app (contacts, call log, phone, microphone, notifications).
2. **Set Callrec Phone as your default phone app.**
3. **Start recording access** (to record *both* sides of a call; skip it to record with the microphone only). In the app tap **Pair and start**, then:
   1. Turn on **Developer options** (tap *Build number* seven times in *About phone*) and, inside it, **Wireless debugging**. Wi-Fi must be on.
   2. Tap **Pair device with pairing code** and leave that box open.
   3. Pull down the notification from Callrec Phone and type the six-digit code into it.
   You only pair once. **After every restart, open the app and tap Start** (Wireless debugging has to be on while it starts). Needs Android 11 or newer.
   *Prefer the Shizuku app?* Install [Shizuku](https://shizuku.rikka.app/download/), start it ([its guide](https://shizuku.rikka.app/guide/setup/)), and allow Callrec Phone in it. Both work; the app uses whichever is on.
4. **Make a short test call.** The recording appears in the **Recordings** tab.

## How recording works

Android only lets *system* apps capture the other person's voice during a call. There is no setting to switch on. The ways around it are root, the phone maker's own recorder, or borrowing the rights of Android's `shell` user, which is what `adb` runs as.

Callrec Phone does the last one **by itself**. Wireless debugging (Android 11+) lets a device be paired with a code and then run shell commands. The app pairs with *its own phone*, then starts a tiny recorder process as the shell user. That process records the call audio and saves it in the app's folder; the app only tells it when to start and stop. This is the same idea as Shizuku, built in, so no second app is needed.

It has the same limits as Shizuku: the recorder process does not survive a restart (tap **Start** again), Wireless debugging has to be on while it starts, and phones can differ in whether call audio can be captured at all. Without it, the app records with the microphone, which hears the other person only on speakerphone.

## Encryption

By default every recording is **sealed with AES-256-GCM** the moment a call ends. No account, no server, no cloud key: this is entirely local.

- **The key** is a random 256-bit key wrapped with a hardware-backed **Android Keystore** key where available (falling back to app-private storage on devices with a broken keystore). A sealed canary is checked on every access, so a lost or changed key is detected before anything is decrypted. The key, the vault and the decrypted cache copies are all excluded from backup and device transfer (`allowBackup="false"` plus `data_extraction_rules.xml`).
- **The file format** is `magic + version + random 12-byte nonce + plaintext length + duration` as authenticated header, then ciphertext with a 16-byte GCM tag. The header is additional authenticated data, so tampering with the length, metadata or nonce — as well as the ciphertext — fails verification. A fresh nonce is generated per file; decryption never exposes partial plaintext on failure.
- **The recorder** runs as a separate shell process that can only write to the app's folder on shared storage, so a finished call lands there as plain audio briefly. The app queues the path in a small durable file, encrypts it into its **private internal** `Vault` folder, wipes the plain file (overwrite + delete), and only then removes the queue entry. A crash at any point is retried at the next launch; files from older versions, or from a period with encryption off, are swept into the vault on startup (never while a call is live).
- **Playback and sharing always decrypt first.** Play, Share and Save to phone get a real `.m4a` in the app's cache, served through a `FileProvider`; WhatsApp and every other recipient therefore receives playable audio. URI read permission is carried on the `ClipData` of the `ACTION_SEND` intent and the chooser, so the system grants it to **only the app the user picks** — the app never enumerates or mass-grants other packages.
- **Save without encryption.** Per recording: *Save to phone (unencrypted)* writes a plain `.m4a` into `Music/Callrec Phone` through `MediaStore` (falling back to the app's own folder if a device rejects the write). Globally: Settings → Encryption → *Save new recordings without encryption* stores new calls as plain audio, and *Save all to phone without encryption* exports every existing recording.
- **Recovery key.** Settings can show (and copy) an offline recovery key and restore one. Use it if a keystore reset ever makes the key unavailable; keep it private — it is the master key. Export and import are both local operations.
- **Edge cases handled:** corrupt/truncated/tampered files are listed as damaged and fail cleanly; a wrong or missing key produces a clear message instead of a crash; deletion removes the encrypted file, its cache copy and temp files; auto-delete (7/30/90 days) applies to encrypted and plain recordings alike; playing while sharing, deleting while playing, and process death mid-encryption are all safe.

Everything above is local file I/O plus the platform keystore. **No network call is made by any part of encryption, decryption, sharing or saving.**

## Security review

A defensive review was made against general security checklists (OWASP/MASVS-style mobile checks, CWE classes, and supply-chain guidance). The following are enforced in code:

- **Untrusted paths are rejected.** The path returned by the separate recorder process, and any line read back from the durable encryption queue, must be a real recording filename whose *canonical* parent is exactly the app's `Recordings` folder. This prevents a forged path from making the app read an arbitrary file into the vault, share its contents, or delete the original. Paths with `|`/newline (queue-injection characters) and symlinks escaping the folder are refused.
- **Least-privilege sharing.** `FileProvider` exposes only three narrow paths (no `root-path`), and the URI grant goes to the chosen app only. Components that do not need to be reachable are `exported="false"`; `CallService` is protected by `BIND_INCALL_SERVICE`; notification `PendingIntent`s are immutable except the one `RemoteInput` requires, which uses an explicit component.
- **Untrusted intents.** `ACTION_DIAL` data is capped and reduced to `tel:`-legal characters before it can reach `TelecomManager.placeCall`. Clipboard pastes into the dialpad are sanitised the same way.
- **Input bounds & secrets.** Recovery-key imports are length-capped before decoding; the recovery key and problem reports are marked `EXTRA_IS_SENSITIVE` so Android masks the clipboard preview; only a short warning (no stack trace) is written to logcat; private, user-written notes are hidden on the call screen while the keyguard is locked.
- **Supply chain.** The Gradle wrapper pins the distribution's SHA-256; deprecated storage/phone permissions from transitive libraries are stripped from the APK; `allowBackup="false"` and `data_extraction_rules.xml` exclude every domain.
- **Verification.** `assembleDebug testDebugUnitTest lintDebug` (unit tests cover round trips, tamper/truncation/wrong-key, filename parsing and the path guard) plus an on-device API 35 smoke test of encryption, keystore wrapping, decryption and the share chooser.

**Residual risks, honestly:** the `callrec` library is fetched from JitPack without Gradle dependency-verification checksums; the software key fallback stores the raw key in app-private storage (protected by the sandbox and file-based encryption, but accessible to root/ADB); the recorder briefly writes plain audio to `Android/data/…/files/Recordings`; decrypted cache copies live for up to two hours; a recovery key is the master key and must be kept offline; real call-audio capture remains OEM-dependent and needs a live device to test.

## Privacy

- Everything stays on the phone. There is no server, no sign-in and no analytics. Calls, contacts, notes and recordings never leave the device.
- **About the network permission.** The app asks for `INTERNET` and a few Wi-Fi permissions. Android requires `INTERNET` for *any* network socket, even one to the phone itself, and that is the only reason: it talks to this phone's own wireless debugging on `127.0.0.1` and looks for its pairing dialog on the local network. Nothing in the code contacts any other server, and the source is here to check (search for `http` or `Socket`). If that is not acceptable to you, the microphone mode and the Shizuku-app mode need none of it, and you can remove the permission and build the app yourself.
- Encrypted recordings are kept in the app's **private internal storage** (`files/Vault`) as `.m4a.enc`; other apps cannot read them. Plain recordings (when encryption is off) live in `Android/data/dev.youbal.callrecphone/files/Recordings`. Sharing is always an explicit action you take, and it always sends decrypted audio.
- Decrypted cache copies live in `cache/shared`, are cleaned up after a day, and are removed when their recording is deleted.
- The recorder process talks to the app through a few small files in that folder, protected by a random secret kept in the app's private storage, so another app cannot give it orders.
- Saved names are read from your contacts only to show and search them, and are not stored by the app.
- Encryption, decryption, sharing and exporting use **no network at all**. The only socket in the app is the local loopback used by built-in recording access (see above); there is no remote endpoint anywhere in the source.

## Recording calls and the law

Recording a call can require the consent of everyone on it, depending on where you live. **You are responsible for following the law.** Tell people when you record them.

## Build it yourself

You need **JDK 17** and the **Android SDK platform `android-37.0`** (API 37 ships as a minor-versioned platform, so `compileSdk 37` / `compileSdkMinor 0` is used). The wrapper uses Gradle 8.14.3 and Android Gradle Plugin 8.13.2.

```
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
git clone https://github.com/Youbal-oops/Phone
cd Phone
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`minSdk 35` means the app installs on **Android 15, 16, 17 and above**. Edge-to-edge insets, predictive back (`OnBackInvokedDispatcher`), scoped storage for exports, `FileProvider` URI grants and the special-use foreground service are all handled for those versions. The bundled native libraries are 16 KB-page aligned for newer devices.

The recording library comes from JitPack. To work on the library and the app together, clone [callrec](https://github.com/Youbal-oops/callrec) **next to this folder**; the build uses that copy automatically.

## Roadmap

- Start the recorder automatically after a restart when Wireless debugging is already on.
- Optional on-device transcription.
- Voicemail and call blocking.

## Licence

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
