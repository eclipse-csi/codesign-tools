# Several executions, several requests

Each `<execution>` of the plugin is one signing request, with its own file selection, artifact
configuration and signing policy. The configuration shared by all executions sits at the plugin
level.

| Execution | Files | Artifact configuration | Signing policy |
| --- | --- | --- | --- |
| `sign-main` | main jar | `jar` | `$SIGNPATH_SIGNING_POLICY` |
| `sign-tools` | `-tools` and `-cli` jars, in one zip | `jars-in-zip` | `${tools.signingPolicy}` (defaults to the same) |

```bash
mvn package
mvn package -Dtools.signingPolicy=another-policy   # sign the second set with another policy
```

**Needs:** artifact configurations `jar` and `jars-in-zip`.
**Result:** the three jars in `target/` are signed, through two signing requests.

Use separate executions when files need different artifact configurations or policies, or
when each file must get its own request (e.g. its own approval).
