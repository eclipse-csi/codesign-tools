# SignPath setup for the demos

The demos sign through a SignPath **project**, with a **signing policy** and an **artifact
configuration**. Both are configured in the SignPath web application, not by the tools. This
folder has the artifact configurations the demos expect.

## Artifact configurations

Create each one in your SignPath project (*Project → Artifact configurations → Add*), paste the
XML, and give it the slug shown below, which is the file name without `.xml`:

| Slug | File | What it accepts | Used by |
| --- | --- | --- | --- |
| `jar` | [`jar.xml`](artifact-configurations/jar.xml) | One jar, sent as is | CLI 01, 03, 05, 06; Maven `adhoc`, `project-artifact`, `multi-execution`, `profile-and-skip`; GitHub Actions `sign-with-action.yml`, `maven-plugin-trusted-build.yml` |
| `jars-in-zip` | [`jars-in-zip.xml`](artifact-configurations/jars-in-zip.xml) | Several jars, packed at the root of a zip | CLI 02; Maven `attached-artifacts`, `multi-execution`; GitHub Actions `cli-direct-mode.yml` |
| `binaries-in-zip` | [`binaries-in-zip.xml`](artifact-configurations/binaries-in-zip.xml) | Any mix of `*.jar`, `*.exe` and `*.dll`, packed at the root of a zip | Maven `includes-output-dir`; GitHub Actions `sign-with-action.yml` |
| `jar-with-parameters` | [`jar-with-parameters.xml`](artifact-configurations/jar-with-parameters.xml) | One jar named `hello-<version>.jar`, with user-defined parameters | CLI 04 |

Which one you need depends on **how many files one invocation signs**, not on the tool or the
submission mode:

- **One file** is sent as is, so the artifact configuration describes the file itself
  (`<jar-file>`, `<pe-file>`, …).
- **Several files** are packed at the root of a zip file and signed in one request, so the
  artifact configuration has a `<zip-file>` root element. A wildcard `path` matches exactly one
  file unless you add `max-matches="unbounded"`.

See [Signing Requests](../../USAGE.md#signing-requests) in USAGE.md and the
[artifact configuration syntax](https://docs.signpath.io/artifact-configuration/syntax).

## Signing policy

Any policy whose certificate can sign jars works, e.g. a `test-signing` policy backed by a
self-signed test certificate. Make the CI user that owns your API token a **submitter** of the
policy.

Policies with **trusted build system verification**, which all Eclipse Foundation policies use,
reject files uploaded directly to the REST API: only the GitHub Actions demos, which submit
through the SignPath GitHub connector, work with them.

## API token

Create a CI user (*Users and groups → CI users*), make it a submitter of the policy, and use its
API token as `CSI_CODESIGN_API_TOKEN`.

## macOS hash signing

Apple code signing does not go through artifact configurations: SignPath has none for apps,
Mach-O binaries, disk images or installer packages. `csi-codesign macos sign` runs the
[SignPath macOS CryptoTokenKit](https://docs.signpath.io/crypto-providers/macos) instead. It
publishes the certificate of a signing policy in the keychain, and `codesign` and `productsign`
send SignPath the hashes to sign. For the [`07-macos-app.sh`](../cli/07-macos-app.sh) and
[`macos-sign-and-notarize.yml`](../github-actions/macos-sign-and-notarize.yml) demos:

- **Project:** its *default* artifact configuration must be of type *hash signing data*. The
  CryptoTokenKit always submits to the default configuration: it has no setting to choose
  another one. The file-based configurations above can stay in the same project, since every
  other demo selects its configuration by slug.
- **Signing policy:** one with the **Developer ID Application** certificate, for apps, binaries
  and disk images. A policy has a single certificate, so `.pkg` installers need a second policy
  with the **Developer ID Installer** certificate, signed in a separate `macos sign` invocation.
  SignPath refuses hash signing under policies with **trusted build system
  verification**, and does not offer policies with origin verification to the CryptoTokenKit at
  all: the policy must have neither. With trusted build system verification, `macos sign` fails
  with SignPath's 403 "The selected signing policy requires that the signing request is
  submitted via a trusted build system."
- **Submitter:** the CI user that owns the API token.
- **Identity:** without `--identity`, `macos sign` uses the certificate of the signing policy.
  Otherwise, pass the common name of the certificate, a distinctive part of it such as the Team
  ID, or its SHA-1 hash. The CryptoTokenKit holds the identity in a token, which
  `security find-identity` does not list: `security export-smartcard -t identities` or
  `system_profiler SPSmartCardsDataType` show it. A self-signed test certificate works too:
  `macos sign` warns that the result is for testing only.

Notarization is a separate, optional step (`csi-codesign macos notarize`). It needs an App Store
Connect API key of the Apple developer team that owns the certificates.
