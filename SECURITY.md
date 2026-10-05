# Security Policy

Redact exists to protect people's privacy, so security and privacy reports are taken seriously.

## Supported versions

Only the latest release on [Google Play](https://play.google.com/store/apps/details?id=com.doubleangels.redact) receives security fixes. Please update before reporting.

## Reporting a vulnerability

**Please do not report security vulnerabilities in a public issue, pull request or discussion.**

Use GitHub's private reporting instead:

1. Open the [**Security** tab](https://github.com/doubleangels/redact/security) of this repository.
2. Choose **Report a vulnerability** and describe the problem.

If that option is not available, open an [issue](https://github.com/doubleangels/redact/issues/new) that only asks for a private way to contact the maintainer. Do not include technical details or exploit steps in it.

Helpful details to include:

- What the problem is and what an attacker could gain
- The Redact version and Android version and device
- Steps to reproduce, or a small proof of concept
- Whether any real personal files or data were involved (please use test files instead)

## What to expect

- An acknowledgement of your report, usually within a few days.
- A fix or a clear explanation of next steps once the issue is confirmed.
- Credit in the release notes if you would like it.

Redact is maintained by one person in their spare time, so please be patient.

## What counts as a security or privacy issue

Examples of things worth reporting:

- Metadata (such as GPS location) surviving **Clean**, **Convert** or share-in when it should have been removed
- Files, filenames, coordinates or other personal data leaving the device without the user's consent
- Crash reports containing information that should have been scrubbed
- Another app being able to read Redact's temporary files or trigger actions it should not

Ordinary bugs, crashes and feature requests belong in a normal [GitHub issue](https://github.com/doubleangels/redact/issues).
