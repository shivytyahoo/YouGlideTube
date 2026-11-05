# Security Policy

## Supported versions

Security fixes land on the latest release line only. Older versions do not
receive backported patches.

| Version | Supported |
| ------- | --------- |
| 1.0.x   | ✅ |
| < 1.0.2 | ❌ |

Always update to the newest release before reporting a security issue.

## Reporting a vulnerability

**Do not report security vulnerabilities through public GitHub issues.**

Report privately through
[GitHub Security Advisories](https://github.com/shivytyahoo/YouGlideTube/security/advisories/new).

Please include:

- Type of issue (for example: credential exposure, path traversal, injection).
- Full paths of the source files involved.
- The affected tag, branch, or commit.
- Any configuration required to reproduce the issue.
- Step-by-step reproduction instructions.
- Proof-of-concept or exploit code, if available.
- Impact, including how an attacker might exploit it.

You will receive an acknowledgement within 48 hours and a timeline for a fix.
Please do not disclose the issue publicly until a fix has shipped.

## Verifying release APKs

Official YouGlideTube builds are signed with a single release key. Any APK that does
not match the fingerprint below is not an official build, regardless of where
it was downloaded.

```
SHA-256: BA:65:7A:2B:F0:6C:A5:70:03:F3:23:A1:4E:6B:BA:11:DC:EA:69:BD:A4:36:09:8F:4C:D7:7C:E5:19:92:A0:48
```

Verify a downloaded APK with the Android SDK build tools:

```bash
apksigner verify --print-certs YouGlideTube-1.0.2.apk
```

The reported `Signer #1 certificate SHA-256 digest` must equal the fingerprint
above (lower case, without colons).

The official distribution channel is the
[GitHub Releases page](https://github.com/shivytyahoo/YouGlideTube/releases).
Builds obtained anywhere else are unverified.
