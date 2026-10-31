# hp-updater — Manual

One `launcher.jar` can serve as many applications as you like. Each application gets a
folder, and the folder has one config file, `app.txt`, in the same style as `getdown.txt`.

---

## 1. The config file

This is the whole thing. On the **client** it needs two lines:

```
appbase = http://172.104.47.59:8080/apps/accord/
ui.name = Saint
```

On the **server** the build tool writes the same file with the release added:

```
appbase = http://172.104.47.59:8080/apps/accord/
ui.name = Saint
version = 1.4.2
class   = org.hp.accord.Main
jvmarg  = -Xmx1024m

code     = Saint.jar|182734|3f9a1c...
code     = lib/SaintLib.jar|48211|71bdc4...
resource = Config.dat|822|9c02ff...
resource = Saint_Launch.exe|241664|55ab90...
```

| Key | Meaning |
|---|---|
| `appbase` | Where to download from. The only line the client really needs. |
| `ui.name` | Name shown in the update window |
| `version` | Set by the build tool |
| `class` | Your main class |
| `jvmarg` | JVM option, repeat for more |
| `apparg` | Argument passed to your app, repeat for more |
| `code` | A file that goes on the classpath (any `.jar`) |
| `resource` | Any other file |

The `|size|checksum` part is added by the build tool. It is what lets the updater resume
a broken download, verify every file, and delete files you removed from the release.

You never write `code` and `resource` lines yourself — the build tool scans your release
folder. Compared to your getdown.txt, the 60-line list of jars disappears.

Optional lines, all with sensible defaults:

```
check-on-start = true   # look for updates on every launch
java           =        # a specific javaw.exe to run the application with
```

---

## 2. Running an application

```
java -jar launcher.jar C:\Apps\Saint
java -jar launcher.jar C:\Apps\ERPX
```

Same launcher, two applications, completely independent: separate servers, separate
versions, separate rollback. Your `.exe` wrapper just passes the folder.

If you only ship one application, put `launcher.jar` and `app.txt` in the same folder and
run it with no argument at all:

```
java -jar launcher.jar
```

Options: `--no-update` (start without checking), `--repair` (re-download damaged files).
Anything else is passed through to your application.

---

## 3. Setting up a new application

### On your PC

Create `app.txt` next to your project:

```
appbase = http://172.104.47.59:8080/apps/accord/
ui.name = Saint
class   = org.hp.accord.Main
jvmarg  = -Xmx1024m
```

Copy `src/org/hp/update/` into your project (it needs no libraries) and build as usual.

### Publish a release

Put exactly what the user should have into a release folder:

```
dist/release/
  Saint.jar
  Config.dat
  Saint_Launch.exe
  lib/SaintLib.jar
  lib/poi-3.15.jar
  ...
```

Then:

```
java -cp dist/Saint.jar org.hp.update.ManifestTool app.txt dist/release 1.4.2 dist/upload
```

| Argument | Meaning |
|---|---|
| `app.txt` | your config from above |
| `dist/release` | what the user should end up with |
| `1.4.2` | version number |
| `dist/upload` | what to upload |

Upload it with the publisher window (**Upload to server**: SFTP, FTPS or FTP), or from the
command line:

```
java -cp publisher.jar org.hp.publish.UploadTool dist/upload sftp://user@host/var/www/apps/accord
```

It sends only files the server does not have, checks every file at the `appbase` URL, and
replaces `app.txt` last. By hand (FileZilla): **`files/` first, `app.txt` last**, because
clients act on `app.txt` the moment it changes.

```
http://172.104.47.59:8080/apps/accord/
  app.txt
  files/3f9a1c5e7b20/Saint.jar        each file keeps its name, in a folder named
  files/71bdc4a0d9e3/poi-3.15.jar     after its checksum
  releases/1.4.2.txt
```

For a second application, use a different folder on the server (`/apps/erpx/`) and a
different `app.txt` with its own `appbase`. Nothing else changes.

### On the user's PC

```
C:\Apps\Saint\
  app.txt        ← the two lines from section 1
C:\Apps\
  launcher.jar
  Saint.exe      ← runs: javaw -jar launcher.jar C:\Apps\Saint
```

First run downloads everything. Afterwards the folder looks like this:

```
C:\Apps\Saint\
  Saint.jar  lib\...   ← the application, exactly like dist
  app.txt  logs\
  .update\             ← hidden: downloads in progress, old files during an update
```

### Ant target

```xml
<target name="release" depends="jar">
  <java classname="org.hp.update.ManifestTool" classpath="dist/Saint.jar" fork="true">
    <arg value="app.txt"/>
    <arg value="dist/release"/>
    <arg value="${app.version}"/>
    <arg value="dist/upload"/>
  </java>
</target>
```

---

## 4. Removing a library

Delete it from the release folder and publish. It disappears from every client on their
next update. That is the whole procedure.

---

## 5. Building the updater into your application

Add `hp-updater.jar` to the project's libraries, then:

```java
public static void main(String[] args) {
    SelfUpdate.ensureUpToDate(args);   // first line: checks, and if needed installs and restarts
    // ... normal startup ...
    SelfUpdate.watch(mainFrame);        // optional: notices new releases while the app is open
}
```

The application then updates itself however it is started, and `launcher.jar` is not
needed. Updates are mandatory by default (`mandatory = true` in `app.txt`): once the server
offers a newer version, the old one is never started again. `allow-offline = true` lets the
installed version start when the server cannot be reached at all.

## 6. What happens when…

| Situation | Result |
|---|---|
| No internet | Update skipped, the application starts normally |
| Connection drops during download | Up to 6 retries per file, continuing where it stopped |
| User closes the app mid-download | Next start continues from the same point |
| User clicks Cancel | Current version stays, partial download kept |
| A downloaded file is corrupted | Detected by checksum and downloaded again |
| New version cannot start (missing file, missing class, wrong Java) | Detected from what the Java runtime reports: the old files are moved back, the old version starts, and that release is skipped until you publish a new one |
| New version starts | The replaced files are deleted and `app.txt` shows the new version number |
| Power cut half way through an update | The old files are put back on the next start, then the update completes |
| A file is in use (a second copy of the app is open) | The update is undone, the old version starts, and the update completes next time with no new download |
| User closes the app, even with an error code | Nothing happens: once it has started, the application is on its own |
| Only `Saint.jar` changed | Only `Saint.jar` is downloaded |
| One application breaks | The others are not affected at all |

---

## 7. Common tasks

**Roll everyone back to an older version** — re-upload the old `app.txt` (a copy of each is in `releases/` on the server). Clients install
whatever version it names, and it is usually still on disk, so there is nothing to download.

**Start without checking for updates** — `javaw -jar launcher.jar C:\Apps\Saint --no-update`

**Fix a damaged installation** — `javaw -jar launcher.jar C:\Apps\Saint --repair`

**Fresh install on one PC** — delete everything in the app folder except `app.txt`.

**Point an application at a different server** — edit `appbase` in the client's `app.txt`.

---

## 8. Troubleshooting

Run with `java` instead of `javaw` to see what happens. Every line starts with `[updater]`.

| Message | Cause |
|---|---|
| `No app.txt in ...` | Wrong folder, or the file was never created |
| `app.txt on the server has no "version" line` | You uploaded your hand-written file instead of the generated one |
| `has no "class" line` | Missing `class =` in your `app.txt` |
| `Could not find or load main class` | Wrong class name in `app.txt` |
| `Checksum mismatch` repeatedly | A file in `files/` on the server is wrong; re-upload it |
| Updates never arrive | Open `<appbase>/app.txt` in a browser and check the `version` line |

---

## 9. Rules to remember

1. Users start `launcher.jar` with the application folder, not the application jar
   directly, or updates never run.
2. Upload `app.txt` **last**.
3. The release folder is exactly what users get. Remove a file there to remove it everywhere.
4. Every ticked file is updated the same way: downloaded when your version of it
   changes. Unticked libraries are deleted from PCs; other unticked files are left as
   they are.
5. `launcher.jar` does not update itself, by design. Keep it as it is; it only needs
   `org.hp.update`.
