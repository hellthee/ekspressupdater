# hp-updater

A getdown-style auto updater you compile **into your own jar**. No external runtime
dependencies, Java 8 compatible, one config file per application.

Written against the three problems getdown gave you:

| Problem | How this solves it |
|---|---|
| A library removed from the list stays on the client | The server `app.txt` is the whole truth. Each release is assembled in a fresh folder containing exactly its files; anything not listed is deleted before the release goes live. |
| A bad connection breaks the installation | Nothing is ever written into the running installation. The new version is assembled in `staging/`, every file verified by SHA-256, and only then does a one-line pointer flip atomically. |
| A failed update leaves the app unusable | The old version stays complete on disk. If the download fails, is cancelled, or the machine is switched off, `current` still points at the old version and the app starts normally. Downloads resume where they stopped, including after a restart. |

Plus automatic **rollback** when a new version fails to start, and `--repair` for damaged
installations.

## Per-client plugins

A client's `lib/set.jar` (on the class path, `Class.forName` as usual) is kept current through
`setapp.txt`: updated at startup like the application; the database supplies the address only
when `setapp.txt` is missing. See **PLUGINS.md**.

## One config file

Client `app.txt` — two lines:

```
appbase = http://172.104.47.59:8080/apps/accord/
ui.name = Saint
```

Server `app.txt` — written by the build tool:

```
appbase = http://172.104.47.59:8080/apps/accord/
ui.name = Saint
version = 1.4.2
class   = org.hp.accord.Main
jvmarg  = -Xmx1024m

code     = Saint.jar|182734|3f9a1c...
code     = lib/SaintLib.jar|48211|71bdc4...
resource = Config.dat|822|9c02ff...
```

You never write the `code`/`resource` lines: the build tool scans your release folder,
so there is no jar list to maintain.

## Several applications, one launcher

```
java -jar launcher.jar C:\Apps\Saint
java -jar launcher.jar C:\Apps\ERPX
```

Each folder has its own `app.txt`, versions and rollback state. They are completely
independent. With one application, put `launcher.jar` next to `app.txt` and pass nothing.

## Publishing

```
java -cp dist/Saint.jar org.hp.update.ManifestTool app.txt dist/release 1.4.2 dist/upload
```

Upload `dist/upload/` to the `appbase` URL with the publisher's **Upload to server**
(SFTP/FTPS/FTP, checks every file at the URL before switching `app.txt`), or by hand:
`files/` first, `app.txt` last. Each file sits in a folder named after its checksum, so a
new version never overwrites one in use, only changed files are uploaded or downloaded,
and any proxy can cache them forever.

Full instructions are in **MANUAL.md**.

## Layout on disk

The application lives flat in its own folder, exactly like `dist`, and runs from there
(the same layout getdown uses), so relative paths in your code just work:

```
C:\Apps\GasX\
  GasX.jar  lib\...  properties-gasx.prp     the application
  launcher.jar  app.txt  logs\
  .update\                                   the updater's record (deleting it is harmless)
    installed.txt        what is installed: version, files, checksums
    staging\1.4.3\       a download in progress, or finished and waiting
    cache\               partial downloads, resumed next time
    backup\              files an update replaced, until the new version has started
    journal.properties   only while an update is being applied
```

Unlike getdown, nothing is downloaded over the installed files. A release is downloaded
and verified in `staging` first, then moved into place; every move is journalled, so a
power cut half way is undone on the next start, and a release that cannot start gets the
old files back.

## Behaviour worth knowing

- **Retries**: 6 attempts per file, exponential backoff with jitter (0.8s → 15s), 15s
  connect / 30s read timeouts. All adjustable in `app.txt` or `UpdateConfig`.
- **Resume**: partial bodies are kept in `cache/` and continued with a `Range` request,
  even across restarts. A server that ignores `Range` causes a clean restart, not corruption.
- **Nothing is trusted until hashed.** A truncated or corrupted body fails SHA-256 and is
  retried; it can never enter a version folder.
- **Unchanged files are never re-downloaded** — a file already in the application folder
  with the right checksum is left where it is.
- **Old files** are kept in `.update\backup\` only until the new version has started,
  then deleted.
- **Two instances** cannot update at once; `update.lock` is a real file lock.
- **Cancelling** is safe at any moment and the partial download is reused next time.

### Caveats

- Every published file is treated alike: downloaded when the publisher's version
  changes. Libraries dropped from a release are deleted; other dropped files are left.
- `launcher.jar` is not auto-updated, by design: it is the one file that must always work.
- Serve over HTTPS. The checksums protect against corruption, not against someone who can
  rewrite `app.txt`. To add tamper protection, sign `app.txt` and verify it in
  `UpdateManager.fetchRemoteManifest()` — that is the one place to change.

## Verified scenarios

`test/run.sh` runs two applications against `test/flaky_server.py`, which supports `Range`
and cuts 70% of connections part way through the body. All pass:

1. Install app A over a connection that keeps dropping → completes by resuming.
2. Install app B with the same `launcher.jar` → independent folder and version.
3. New release of A with a library removed → the file is gone, unchanged files reused,
   only the changed file downloaded, **B untouched**.
4. Server unreachable → both apps still start on their installed versions.
5. Server back → the interrupted download resumes and the update completes.
6. A deliberately broken release → it activates, fails to start, and the launcher rolls
   back to the previous version and starts that instead.
7. Single-application setup with `launcher.jar` and `app.txt` in one folder, no argument.
