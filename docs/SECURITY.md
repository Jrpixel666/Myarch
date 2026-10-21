# Security model

Myarch downloads a Linux distribution and runs its code. That deserves to be
stated plainly rather than buried.

## What Myarch can and cannot do

The container runs as **your app's user**, inside your app's sandbox. proot
fakes `chroot` and uid 0 with `ptrace`; it grants no actual privilege. So
everything in the container can do exactly what Myarch itself can do, and
nothing more:

- It can read and write the app's own data directory, and the app's external
  files directory (bound at `/mnt/android`).
- It can use the network, because Myarch holds `INTERNET`.
- It **cannot** read your photos, messages, contacts or other apps' data —
  Myarch requests none of those permissions.
- It **cannot** survive uninstalling the app, or escape to the rest of the
  system.

A rooted device changes this. If you grant Myarch root, the container has root.

## What you are trusting

**The Arch Linux ARM mirrors.** The rootfs is downloaded over HTTP or HTTPS from
a public mirror. Arch Linux ARM does not publish per-image checksums alongside
its `-latest` tarballs, so there is no signature to verify the image against.
A mirror that served you a modified rootfs would be serving modified code that
then runs in the container. Myarch reduces the blast radius but cannot close
this:

- The download's SHA-256 is computed and logged, so two installs can be
  compared, and an expected checksum is verified when one is supplied.
- A short transfer is a failure, not a success.
- An archive that yields implausibly few files is rejected — that is what a
  truncated download or a captive portal's error page looks like.
- Compression is detected from magic bytes rather than the file name.

Prefer the HTTPS mirrors (Florida, California, Germany) if this matters to you.

**Packages.** These *are* verified. `first-boot.sh` initialises the pacman
keyring, and pacman checks signatures against the Arch Linux ARM keys for every
package it installs.

**proot, talloc and libandroid-shmem.** Fetched from Termux's package
repository over HTTPS at build time. The exact versions and SHA-256 sums are
recorded in `app/src/main/jniLibs/VENDORED.json`, so a build is auditable after
the fact. See [THIRD_PARTY.md](THIRD_PARTY.md).

**Whatever you install.** `pacman -S` and the AUR build script run arbitrary
upstream code. That is the point of having a package manager, and it is the
same trust you extend on any Arch machine.

## The compositor's own exposure

The Wayland socket lives in the app's private directory and is bound with
filesystem permissions, so only this app's uid can connect — there is no
network listener anywhere in Myarch.

A Wayland client can still misbehave, so the compositor treats client input as
untrusted:

- Buffer geometry is validated against the pool's real size, in 64-bit
  arithmetic, because the product of three attacker-chosen ints overflows.
- A descriptor that cannot be mapped (a pipe, a lie about the size) is a
  protocol error, not a crash.
- Paths in the rootfs archive that escape the destination are refused.
- An unparseable message drops that one client; a bug in our own handling is
  reported as an implementation error and the session continues.
- Surface role chains are depth-limited, so a client cannot recurse us to death.

These are enforced by tests in `CompositorSessionTest` and `TarExtractorTest`.

## `targetSdk` 28

Myarch targets an old SDK level so the container can execute its own binaries;
[ANDROID_EXEC_CONSTRAINTS.md](ANDROID_EXEC_CONSTRAINTS.md) explains why in
detail. What that costs you in security terms: the app is not subject to scoped
storage or the newer permission model. It still requests only the permissions
listed in the manifest, and the container is still confined to the app sandbox
by the kernel and SELinux — the old target weakens the *framework's* checks, not
the sandbox itself.

## Reporting something

Open an issue, or for something you would rather not post publicly, use GitHub's
private vulnerability reporting on this repository.
