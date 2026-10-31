# hp-updater — to do (future upgrades)

## 1. Release signing (switch it on)

**Why:** today a PC installs whatever its `appbase` serves. Over plain `http://` someone on
the customer's network could serve a fake `app.txt` (another main class, a `-javaagent`
JVM option, other jars) and run code on that PC. HTTPS reduces this to "whoever controls
the server or the upload login".

**Already in the code, dormant:**
- `Signing.java` — ECDSA P-256 (SHA256withECDSA, Java 8), `signature =` line inside
  `app.txt`, key file `~/.hp-publisher/signing-key.txt` (or `HP_SIGNING_KEY`).
- `ReleaseBuilder` signs only when `Options.signingKey` is set or `-Dhp.sign=true`.
- PCs whose `app.txt` has a `publickey =` line refuse releases not signed with that key
  (tested: `uptest/audit.sh` A3). PCs without one accept anything and do **not** adopt a
  key from the server.
- `UpdateConfig.rememberPublicKey()` exists but is unused.

**Still to do before switching it on:**
- [ ] Publisher: a "Sign releases" setting; show the key fingerprint and the key file path.
- [ ] First-time warning: back up the key file; without it PCs refuse every future release.
- [ ] Moving the key between Windows and the Mac (export / import button).
- [ ] Decide how existing PCs get the key: ship `publickey` in the client `app.txt` only,
      or "trust on first use" (PC remembers the first signed release's key). TOFU is
      convenient but a forged first release could pin the wrong key — only enable it
      together with HTTPS.
- [ ] Key change / lost key procedure (a release signed by the old key that announces the
      new one).
- [ ] Docs: SETUP, INTEGRATE, MANUAL.
- [ ] Re-enable the signing tests in `uptest/audit.sh` (forged release, foreign key).
- [ ] Plugins (set.jar via `PluginUpdate`/setapp.txt) already use the PC's pinned key from the main
      app.txt; sign plugin releases too and test with `uptest/settest.sh`.

## 2. Other ideas noted during the audit

- [ ] `launcher.jar` does not update itself (by design, MANUAL item 5). If it ever needs a
      fix, every PC needs a new copy by hand — or GasX's built-in updater could refresh it.
- [ ] Optional: check that the install folder is not writable by other users and warn.
- [ ] Slow-trickle cutoff is 60 s per attempt; consider making it configurable.
