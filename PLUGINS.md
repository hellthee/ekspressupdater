# Per-client set.jar: `lib/set.jar`, kept current through `setapp.txt`

Each client has its own `set.jar` (Jasper reports, number formats, `com.sbm.option` classes).
It sits in `lib\` on Saint's normal class path, so **`Class.forName(...)` works as it always
did.**

How the PC knows which set it needs:

- **`setapp.txt`** next to `app.txt` remembers where the set comes from and its version. With
  that file present, the set is updated **at startup, before Saint opens it**, exactly like
  Saint itself. No database, no restart.
- **The database** holds the set's address (one string column). It's read only when
  `setapp.txt` is missing: a first install, or a deleted or damaged file.
- **Only then is there a restart.** After login the set is downloaded, `setapp.txt` is
  written, your code records the version in the database and closes the connection, and Saint
  restarts once. The updater swaps `lib\set.jar` while Saint is closed.

```
server                                        client database
apps/accord/                 Saint itself      company.set_url =
apps/accord/sets/pt-abc/     PT ABC's set  <--   'http://172.104.47.59:8080/apps/accord/sets/pt-abc/'
apps/accord/sets/cv-xyz/     CV XYZ's set      company.set_version = '1.0.3'   (written by Saint)

PC  C:\Saint\
  app.txt          Saint's address
  setapp.txt       plugin = set / appbase = …/sets/pt-abc/ / version = 1.0.3
  lib\set.jar      PT ABC's set, on the class path
  plugins\set\     the last downloaded set (the source lib\set.jar is copied from)
```

---

## 1. Database

```sql
ALTER TABLE company ADD COLUMN set_url     VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE company ADD COLUMN set_version VARCHAR(40)  NOT NULL DEFAULT '';
UPDATE company SET set_url = 'http://172.104.47.59:8080/apps/accord/sets/pt-abc/';
```

`set_url` is set once per client. `set_version` is written by Saint, so you can see which set
each client runs. Both are plain strings, so backup and restore stay simple.

## 2. Saint's code

### First line of `main` (as for GasX)

```java
public static void main(String[] args) {
    SelfUpdate.ensureUpToDate(args);   // updates Saint AND the set before anything opens set.jar
    ...
}
```

### After login: one call

Put it right after the connection is open and **before the first `Class.forName` of a set
class**:

```java
PluginUpdate.ensure("set", mainFrame, new PluginUpdate.Source() {

    @Override
    public String address() throws Exception {
        // Only called when this PC has no usable setapp.txt.
        PreparedStatement ps = con.prepareStatement("SELECT set_url FROM company LIMIT 1");
        ResultSet rs = ps.executeQuery();
        String url = rs.next() ? rs.getString(1) : "";
        rs.close();
        ps.close();
        return url;
    }

    @Override
    public void installed(String version) throws Exception {
        // A new set is downloaded and setapp.txt records it. Saint restarts right after this.
        PreparedStatement ps = con.prepareStatement("UPDATE company SET set_version = ?");
        ps.setString(1, version);
        ps.executeUpdate();
        ps.close();
        con.close();
    }
});

// from here on, as before:
Class<?> c = Class.forName("com.sbm.option.DeliveryOrderReceipt");
```

`ensure` either returns, meaning the right set is in `lib\set.jar` and you carry on, or it
restarts Saint and never returns. It throws `IOException` with a message for the user when no
usable set exists (see section 5).

Wrap it the way you handle other startup errors:

```java
try {
    PluginUpdate.ensure("set", mainFrame, source);
} catch (IOException e) {
    JOptionPane.showMessageDialog(mainFrame, e.getMessage(), "Report set", JOptionPane.ERROR_MESSAGE);
    System.exit(1);
}
```

It's safe to call from the login button's handler (the Swing thread). The window keeps
repainting while it works.

### Keeping `set_version` current after a startup update

A set updated at startup is updated before any login, so there's no database yet. After login,
if you want the column to stay exact:

```java
if (PluginUpdate.justUpdated("set")) {
    // UPDATE company SET set_version = PluginUpdate.version("set")
}
```

`PluginUpdate.justUpdated("set")` is also true on the first start after the restart that
`ensure` caused. Use it if you'd like to skip asking for the password a second time.

### A PC used with several companies

If one PC logs into different client databases, compare the address at every login. It's one
small query, and a restart only happens when the company actually changes:

```java
PluginUpdate.ensure("set", setUrlFromDatabase, mainFrame, source);
```

## 3. Saint's NetBeans project and release

- **Keep** the set in Saint's Libraries, so `Saint.jar`'s manifest `Class-Path` lists
  `lib/set.jar` and `Class.forName` finds it, in NetBeans as well.
- **Don't publish it with Saint.** In the publisher, untick `lib/set.jar` (saved in Saint's
  `app.txt` as `# exclude = lib/set.jar`). If a release still carries one, the updater leaves
  the client's `lib\set.jar` alone and logs *"Leaving [lib/set.jar] to the plugin updater"*.
- Replace `launcher.jar` on the PCs with the new one when convenient. An old launcher still
  works, because Saint itself finds a new set at login, but it costs one restart per new set.

## 4. Publishing a client's set

The set is published from the **same build folder** as the application, in the publisher's
**Set** tab:

1. Build the application with that client's set (so `dist\lib\set.jar` is theirs).
2. **Set** tab:

| Field | PT ABC example |
|---|---|
| Download URL | `http://172.104.47.59:8080/apps/gasx/sets/pt-abc/` |
| Set name | `GasX set PT ABC` |
| Version | suggested from `upload-set\pt-abc` (last setup of this client) |
| Files | tick `lib/set.jar` and any supporting files: `config.dat`, images, `.prp` |

3. **Create setup**, then **Upload** (server folder `…/apps/gasx/sets/pt-abc`). The log shows
   the address for the client's database (`set_url`).

Rules:
- **One file, one release.** A file ticked in **Set** can't be ticked in **Application**;
  there it shows "in Set tab". The application release never carries a set file.
- **Same paths on the PC.** `lib/set.jar` goes to `lib\set.jar`, `config.dat` to the
  application folder, `images/logo.png` to `images\`.
- **Customer copies of non-jar files are kept.** A `config.dat` or `.prp` the customer
  changed is replaced only when you change that file in a new set release. Jars are always
  exactly as released.
- **Check all / Uncheck all** act on one tab only. Check all never takes a file the other tab
  has.
- Output: `upload` for the application, `upload-set\<client>` for each client's set (the last
  part of the set's Download URL).
- Every PC of that client gets the new set at its next start. No database change, no restart.

The settings of both tabs are saved in the project's `app.txt` (in `dist` and next to
`build.xml`; commit it to Git). The set's part is stored as `# set.…` lines.

## 5. What happens

| Situation | Result |
|---|---|
| New set published | Downloaded and put in place at the next start, before Saint opens it. No DB, no restart |
| Normal start, set current | Nothing extra (one small check of the server at startup) |
| `setapp.txt` missing or damaged, `lib\set.jar` correct | At login: DB asked once, `setapp.txt` written again. No download, no restart |
| `setapp.txt` missing, set missing or wrong (first install) | At login: DB asked, set downloaded, `installed(version)`, notice, **one restart** |
| `lib\set.jar` deleted | Put back at startup from `plugins\set`. No DB |
| `plugins\set` damaged | Downloaded again at startup |
| Server unreachable | The set in place is used |
| Server unreachable on a PC that never had the set | `ensure` throws: *"…has not been downloaded to this PC yet… check the internet connection"* |
| `lib\set.jar` in use by another Saint (Windows) | That start carries on with the current set and says why. The new set goes in at the next start, without downloading again |
| A restart is needed while another Saint is open | `ensure` throws *"another copy of Saint is open. Close it, then start Saint again"*. Nothing is changed and the DB isn't touched |
| Two clients with the same set version number | Told apart by their files, never mixed |
| Address in DB is Saint's own folder, or not `http(s)://` | `ensure` throws with the reason |
| Running from NetBeans | Nothing downloaded. The set on the project's class path is used |
| Restart doesn't help (should never happen) | After two tries `ensure` stops restarting and throws |

## 6. Security

Whoever can change `set_url` in a database decides which code that client's Saint runs, and
releases aren't signed yet (TODO.md). Treat write access to that column like access to your
server, and prefer `https://` addresses.
