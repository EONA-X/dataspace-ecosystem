# SFTP Source

EDC data-plane extension that reads a single flat file from a provider-owned SFTP server as the source side of an `HttpData-PULL` transfer — no new DSP transfer type, no push, no directory listing.

## Table of Contents

- [Overview](#overview)
- [Problem it Solves](#problem-it-solves)
- [How It Works](#how-it-works)
- [Architecture](#architecture)
- [Usage](#usage)
- [Authentication](#authentication)
- [Host Key Verification](#host-key-verification)
- [Error Handling](#error-handling)
- [Wiring / Deployment](#wiring--deployment)
- [Testing](#testing)
- [Functional / Live Testing](#functional--live-testing)

## Overview

Some participants (e.g. ADP) expose data as flat files on an SFTP server they operate themselves, outside the dataspace. This extension lets a provider describe such a file as a standard EDC asset — a `Sftp-Pull` `DataAddress` — and have the data-plane relay it to a consumer through the existing `HttpData-PULL` mechanism: negotiation and transfer look exactly like any other asset, the SFTP hop is purely on the provider's data-plane, read-only.

It is a source-only extension: there is no SFTP sink, and it never writes to, lists, or deletes anything on the remote server — it opens the one file named on the `DataAddress` and streams it.

## Problem it Solves

Providers whose data already lives on an SFTP server would otherwise have to stand up an HTTP endpoint (or some other bridge) in front of it just to publish it as a dataspace asset. This extension removes that step: the data-plane talks SFTP directly to the provider's own server, using credentials resolved from the Vault at transfer time — the provider never has to expose the file over HTTP or copy it anywhere.

## How It Works

1. A provider creates a `Sftp-Pull` asset whose `DataAddress` carries `host`, `port`, `path`, `username`, `authType` and Vault aliases for the credential and the expected host key (see [Usage](#usage)).
2. At transfer time, `SftpDataSourceFactory` — registered with EDC's `PipelineService` for the `Sftp-Pull` type — validates the address and builds an `SftpDataSource` wrapping an `SftpClientWrapper` for that address. No I/O happens yet.
3. When the consumer's request reaches the data-plane and the source's single `Part` is actually read (`SftpPart#openStream`), `SftpClientWrapper` connects: opens the SSH session, verifies the server's host key against the Vault-resolved fingerprint (fail-closed), authenticates (password or private key, also from the Vault), then opens the remote file via SFTP and returns its `InputStream`.
4. The data-plane streams that content back to the consumer exactly as it would for an `HttpData` source. Closing the source closes the SSH session.

Everything is lazy: building the `DataSourceFactory`, the `DataSource`, or the `SftpClientWrapper` never opens a socket — only the first `openStream()`/`openInputStream()` call does, consistent with `PipelineServiceImpl` creating a new source per request.

## Architecture

The extension follows the standard EDC SPI / Core / Extension separation, purely additive on top of upstream `Connector` code (no modification to it):

```
sftp-source/
├── build.gradle.kts
├── src/main/java/.../sftp/
│   ├── SftpSourceExtension.java     # ServiceExtension: injects PipelineService + Vault, registers the factory
│   ├── SftpDataSourceFactory.java   # DataSourceFactory: validates the DataAddress, builds SftpDataSource
│   ├── SftpDataSource.java          # DataSource: exposes the single remote file as one Part
│   ├── SftpPart.java                # DataSource.Part: name/mediaType/openStream() for that file
│   ├── SftpClientWrapper.java       # SSH/SFTP connection, host-key check, auth, single-file read — AutoCloseable
│   ├── SftpDataAddress.java         # DataAddress wrapper: host/port/path/credentials/host-key properties
│   └── SshKeyLoader.java            # Parses a PEM private key into KeyPair(s) via MINA SSHD, algorithm-agnostic
├── src/main/resources/META-INF/services/
│   └── org.eclipse.edc.spi.system.ServiceExtension   # ServiceLoader entry, discovered at boot
└── src/test/java/.../sftp/
    ├── SftpDataSourceFactoryTest.java       # validateRequest/createSource, no real SSH connection
    └── SftpDataSourceIntegrationTest.java   # real SFTP server (Testcontainers, atmoz/sftp), end to end
```

Dependencies (`build.gradle.kts`): `edc.spi.dpf` (for `DataSourceFactory`/`DataSource`/`PipelineService`), `edc.spi.core` (for `Vault`, `Monitor`, `EdcException`), and Apache MINA SSHD (`mina.sshd.core`, `mina.sshd.sftp`) for the SSH/SFTP client itself.

## Usage

Every connection parameter is carried on the asset's `DataAddress` — this connector holds no connector-wide SFTP configuration, since each participant's server (host, port, credentials, host key) is different:

```json
{
  "type": "Sftp-Pull",
  "host": "sftp.provider.example.com",
  "port": "22",
  "path": "/upload/report.csv",
  "username": "adp",
  "authType": "password",
  "clientPasswordSecretAlias": "sftp-provider-password",
  "hostKeyFingerprintSecretAlias": "sftp-provider-hostkey",
  "contentType": "text/csv"
}
```

- `host`, `path`, `username` — required.
- `port` — optional, defaults to `22`.
- `contentType` — optional; if omitted, guessed from the file name extension, falling back to `application/octet-stream`.
- The file's name on the transfer (and as the `DataSource.Part` name) is the last path segment of `path`.

## Authentication

`authType` selects the credential mechanism (defaults to `password` when omitted):

- `password` — requires `clientPasswordSecretAlias`, a Vault alias resolving to the SFTP password.
- `key` — requires `privateKeySecretAlias`, a Vault alias resolving to a PEM-encoded private key (RSA, Ed25519, ECDSA — parsed generically via MINA SSHD, not a hard-coded key type). `privateKeyPassphraseSecretAlias` is optional, for an encrypted key.

Credentials are **never** stored as raw values on the `DataAddress` — only Vault aliases — and are resolved at connection time, mirroring `HeaderSecretParamsDecorator` (`data-plane-http-header-secrets`).

## Host Key Verification

`hostKeyFingerprintSecretAlias` is **mandatory** on every `Sftp-Pull` address (`SftpDataSourceFactory#validateRequest` rejects its absence) and must resolve to the SHA-256 fingerprint (`SHA256:...`, as printed by `ssh-keygen -lf`) of the server's host key.

Verification is fail-closed: the actual server key's fingerprint is computed the same way and compared exactly (case-sensitive) against the Vault-resolved value. There is no fallback to a local `known_hosts` file and no "trust on first use" — a mismatch fails the connection and logs a `severe` entry naming both fingerprints, since a connector-wide trust store would be meaningless when every participant runs its own SFTP server.

## Error Handling

Every failure path raises `EdcException` with a message naming the host/port and, where relevant, the offending Vault alias:

- Missing required `DataAddress` properties (`host`, `path`, `hostKeyFingerprintSecretAlias`, the credential alias matching `authType`) are caught by `validateRequest`, before any connection is attempted.
- A Vault alias that fails to resolve (`null`) at connection time — host key, password, private key or passphrase — fails with the alias name in the message.
- A host key mismatch, a failed SSH connection/authentication, or an I/O error while reading the remote file all fail with `EdcException`, and the SSH session/client are always closed (`SftpClientWrapper#close`), never leaked.

## Wiring / Deployment

The module is included in the Gradle build (`settings.gradle.kts`) and pulled into the data-plane launcher as a `runtimeOnly` dependency:

```kotlin
// launchers/data-plane/data-plane-base/build.gradle.kts
runtimeOnly(project(":extensions:data-plane:sftp-source"))
```

Being on the runtime classpath is sufficient — the `ServiceLoader` entry registers `SftpSourceExtension` automatically with the EDC runtime; no further configuration is required.

## Testing

- `SftpDataSourceFactoryTest` — `validateRequest`/`createSource` against a mocked `Vault`, no real SSH connection: required-property validation for both `authType`s, file name derivation, content-type defaulting.
- `SftpDataSourceIntegrationTest` — a real SFTP server via Testcontainers (`atmoz/sftp`), driven through the production `SftpDataSourceFactory`/`DataSource` path: successful byte-exact read, wrong password, wrong/missing host key, a missing password alias (including the case where the SSH session is already open when the alias fails to resolve, to prove no session/client leak).

Run just this module's checks:

```bash
./gradlew :extensions:data-plane:sftp-source:test :extensions:data-plane:sftp-source:checkstyleMain
```

## Functional / Live Testing

Beyond the Testcontainers integration test, the extension was validated end to end on a Lourmel playground (real K3s dataspace: provider + consumer connectors, identity-hub, real Vault, real data-plane) against an actual external SFTP server, mirroring how a participant such as ADP would be set up:

1. Vault secrets created for the SFTP password and the server's host-key fingerprint.
2. A `Sftp-Pull` asset created pointing at a real file (`eonax.png`) on the external SFTP server.
3. Full negotiation → transfer → `HttpData-PULL` retrieval on the consumer side.

Result: byte-exact match between the file on the SFTP server and what the consumer received — confirming the wiring (extension registration, Vault resolution, host-key check, real network SFTP read) works outside the mocked/containerized test suite, not just in isolation.
