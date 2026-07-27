# usblogcapture

Implements the design in `usb-log-capture-architecture.md` (the earlier handoff doc)
as a set of single-responsibility classes meant to drop into your existing app module.
Package name is `com.example.usblogcapture` throughout — rename to match your actual
namespace before building.

## Layout

```
src/main/aidl/.../ILogFileHandoff.aidl     cross-profile IPC contract

src/main/java/.../volume/                  is this volume really here, right now?
  UsbEvents.kt                               REAL_INSERT / VISIBILITY_ONLY / REAL_REMOVAL / SWITCH_INDUCED
  UsbVolumeTracker.kt                        the classifier — StorageManager is the source of truth
  UsbVolumeResolver.kt                       resolves a volume's root in the current namespace

  crossuser/                                 the reflection-heavy boundary, isolated
  CrossUserUsbReceiver.kt                    cross-profile broadcasts -> classified events
  UserSwitchGate.kt                          IUserSwitchObserver as a plain callback contract
  ProfileServiceConnector.kt                 local shortcut vs bindServiceAsUser, AIDL adaptation
  ReflectionPlaceholders.kt                  TODOs marking your existing reflection calls

  handoff/                                   per-profile file access
  HandoffApi.kt                              contract shared by local + remote paths
  LogFileHandoffImpl.kt                      actual file logic (read config / open append)
  LogFileHandoffService.kt                   thin Binder wrapper around the above
  SessionConfigParser.kt                     log.sinfo -> package list

  session/                                   session lifecycle, decoupled from switches
  SessionInfo.kt
  SessionRepository.kt                       persistence, for crash recovery
  UsbLogSessionOrchestrator.kt               the coordinator — ties everything above together

  capture/                                   <-- your integration point
  LogCaptureEngine.kt                        interface your existing mechanism implements
  FileLogSink.kt                             the only class touching a real fd; buffers across switches
  NoOpLogCaptureEngine.kt                    placeholder, delete once you wire in the real one

UsbLogCaptureService.kt                      composition root (owner-profile foreground service)
AndroidManifest.snippet.xml                  service declarations + permissions
```

## Wiring in your existing log capture mechanism

This is the one integration point the whole module was built around: implement
`LogCaptureEngine`, and pass an instance of it into `UsbLogSessionOrchestrator` in
`UsbLogCaptureService.onCreate()` instead of `NoOpLogCaptureEngine()`. Your
implementation receives a package filter and a `LogSink` to write bytes to — it
never sees USB volumes, profiles, or switch events, so nothing about *how* you
capture logs needs to change to fit this subsystem.

## Other wiring (things you already have working)

Three spots are marked `TODO`/placeholder rather than reimplemented, because you
already have working versions and guessing at the exact reflection calls would be
worse than leaving them explicit:

- `CrossUserBroadcasts.registerAcrossAllUsers` — your existing cross-user receiver registration.
- `CrossUserBind.bindServiceAsUser` — your existing bindServiceAsUser reflection call.
- `UsbLogCaptureService.resolveCurrentForegroundUserId()` — however you already read
  `ActivityManager.getCurrentUser()`.
- Your `IUserSwitchObserver` implementation should call `switchGate.dispatchBeforeSwitch(...)`
  from `onUserSwitching(newUserId, reply)` and `switchGate.dispatchSwitchComplete(...)`
  from `onUserSwitchComplete(newUserId)`.

## Known follow-ups, not yet handled

- `ProfileServiceConnector`/AIDL calls can throw `RemoteException`/`DeadObjectException`
  if the bound process dies mid-call (e.g. a second switch arriving very fast) —
  wrap `withHandoff` call sites accordingly once you see how often this actually happens.
- `resumeOnCurrentProfile()` doesn't retry if `openForAppend` fails because the volume
  isn't visible yet at that exact instant — add a short retry/backoff if this shows up
  in practice.
- Reinserting a previously-seen volume after a real ejection currently always starts a
  new session (new id, new filename). To resume the old file instead, check
  `SessionRepository` for a prior session keyed by `volumeUuid` inside `onRealInsert`
  before generating a new one.
