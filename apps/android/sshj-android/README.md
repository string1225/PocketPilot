# PocketPilot SSHJ Android compatibility build

This module produces a deterministic source-built compatibility JAR for SSHJ
`0.40.0`. No third-party binary is committed to this repository.

## Upstream

- Project: `hierynomus/sshj`
- Source tag: `v0.40.0`
- Maven coordinate: `com.hierynomus:sshj:0.40.0`
- Upstream JAR SHA-256:
  `a6a5533f6580e0418dfcacbb5396680186698585c325fa950c788e749f24d1e2`
- Source: <https://github.com/hierynomus/sshj/tree/v0.40.0>

The build verifies both the complete upstream JAR hash and the hashes of every
class it replaces. An upstream artifact or class change fails the build before
compilation so the patch cannot silently apply to a different SSHJ release.

## Why this exists

Android exposes a reduced platform provider named `BC`, and default Ed25519
`KeyFactory` lookup can select `AndroidKeyStore`. The former lacks primitives
required by SSHJ 0.40; the latter cannot import remote X.509 or user PKCS#8
keys. Registering or replacing a process-wide provider would affect unrelated
cryptography in the app.

The compatibility build retains every SSHJ 0.40 class except these upstream
classes, which are rebuilt from their Apache-2.0 sources:

- `net.schmizz.sshj.common.Ed25519KeyFactory`
- `com.hierynomus.sshj.signature.SignatureEdDSA` and its nested `Factory`

The only code-level semantic change is that Ed25519 key parsing and signatures use an
app-bundled `BouncyCastleProvider` **instance** through JCA overloads accepting
a `Provider` object. The instance is never passed to `Security.addProvider`,
`insertProviderAt`, or `removeProvider`. All SSHJ digests, KEX primitives,
ciphers, MACs, RSA, and EC operations continue using Android's default JCA
selection. The output intentionally replaces the upstream OSGi/module manifest
with a minimal PocketPilot build manifest because it is consumed only as an
internal Android classpath dependency.

## Reproducibility and maintenance

`build.gradle.kts` strips the three pinned upstream class files, adds the
reviewable replacements, removes signature metadata, normalizes ZIP ordering
and timestamps, and verifies the resulting JAR SHA-256. To update SSHJ:

1. Review the corresponding upstream sources and security release notes.
2. Rebase the two source patches.
3. Update the pinned upstream JAR and class hashes.
4. Build twice with JDK 17 and confirm identical output hashes.
5. Update the pinned patched JAR hash.
6. Run JVM, Android instrumentation, password SSH, and Ed25519 private-key SSH
   integration tests before merging.

See `LICENSE-SSHJ.txt` and `NOTICE-SSHJ.txt` for upstream attribution.
