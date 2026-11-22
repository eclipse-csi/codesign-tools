# Sign only when asked

The plugin execution lives in a `codesign` profile, so that everyday builds (developers,
pull requests), which have no SignPath token, don't sign. Release builds turn it on. The signing
policy is a property, so the same POM serves test and release signing.

```bash
mvn package                                                    # no signing
mvn package -Pcodesign                                         # signs with $SIGNPATH_SIGNING_POLICY
mvn package -Pcodesign -Dcodesign.signingPolicy=release-signing
mvn package -Pcodesign -Dcsi.codesign.skip=true                # profile on, signing skipped
CSI_CODESIGN_SKIP_SIGNING=true mvn package -Pcodesign          # same, from the environment
```

`CSI_CODESIGN_SKIP_SIGNING` (`1`, `true` or `yes`) skips signing whatever the POM says, which
lets a CI system turn signing off without touching the build command.

**Needs:** artifact configuration `jar`.
**Result:** with `-Pcodesign`, `target/profile-and-skip-1.0.0-SNAPSHOT.jar` is signed.
