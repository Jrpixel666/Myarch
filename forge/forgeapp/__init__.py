"""Forge: turn a one-line idea into an installable Android WebView APK.

A small, dependency-light companion to Myarch. You describe an app in plain
language ("next bus from my location"); Forge asks Claude to write it as a web
app, drops it into a fixed, known-good Android WebView project, and builds an
APK with the same Gradle/AGP toolchain Myarch itself uses.

Honest scope: builds take minutes, not seconds, and arbitrary ideas are
best-effort generation, not a guarantee. See forge/README.md.
"""

__version__ = "0.1.0"
