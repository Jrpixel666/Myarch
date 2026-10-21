# Mirrors

Downloading a Linux distribution over a phone connection fails in ways a
desktop installer never has to think about: captive portals, networks that block
plain HTTP, mirrors that are down in one country and fine in another, and
connections that drop halfway through 600 MB. The mirror switcher exists because
of that, not as a power-user setting.

## How selection works

`MirrorRotation` (in `:runtime`) does two jobs:

**Ordering.** Before downloading, Myarch sends a one-byte range request to each
mirror's rootfs URL. Mirrors that answer are sorted by latency; mirrors that do
not are dropped for this run. Without probe results, the ones marked *known
good* sort first.

**Fallback.** When a download fails, that mirror is retired and the next
candidate is used, automatically. The installer just retries in a loop. Only
when every option is spent does it ask the user to intervene.

A mirror the user picked explicitly is **pinned**: it is tried first and, on
failure, moved to the back of the rotation rather than dropped. Silently
overriding an explicit choice is how someone ends up downloading 700 MB from the
other side of the planet.

## The bundled list

| Id | Where | URL | Verified |
|---|---|---|---|
| `alarm-geo` | Global redirector | `http://os.archlinuxarm.org` | yes |
| `alarm-fl-us` | Florida, US | `https://fl.us.mirror.archlinuxarm.org` | yes |
| `alarm-ca-us` | California, US | `https://ca.us.mirror.archlinuxarm.org` | yes |
| `alarm-de3` | Germany | `https://de3.mirror.archlinuxarm.org` | yes |
| `alarm-tx-us` | Texas, US | `http://tx.us.mirror.archlinuxarm.org` | |
| `alarm-il-us` | Illinois, US | `http://il.us.mirror.archlinuxarm.org` | |
| `alarm-sg` | Singapore | `http://sg.mirror.archlinuxarm.org` | |
| `alarm-au` | Australia | `http://au.mirror.archlinuxarm.org` | |
| `alarm-jp` | Japan | `http://jp.mirror.archlinuxarm.org` | |

"Verified" means the host answered a range request when the list was last
reviewed. The unverified ones are still offered, because reachability depends on
where the phone is — a mirror unreachable from one network is often the fastest
from another.

`os.archlinuxarm.org` is first because it redirects to whichever mirror is close
to the client, which is usually the best answer. Redirects between HTTP and HTTPS
are followed.

## Re-checking the list

```sh
for host in os.archlinuxarm.org fl.us.mirror.archlinuxarm.org de3.mirror.archlinuxarm.org; do
  code=$(curl -sSL -o /dev/null -m 20 -r 0-100 -w '%{http_code}' \
    "https://$host/os/ArchLinuxARM-aarch64-latest.tar.gz")
  echo "$code  $host"
done
```

`200` or `206` means usable. Update `verified` in
`runtime/src/main/kotlin/dev/myarch/runtime/mirror/Mirror.kt` and this table
together; `MirrorRotationTest` checks the list stays well formed.

## URLs

Rootfs images: `<base>/os/ArchLinuxARM-<arch>-latest.tar.gz`, where `<arch>` is
`aarch64`, `armv7` or `x86_64`. Note that the 32-bit image is published as
`armv7`, not the `armv7h` package architecture — a mismatch that is easy to get
wrong and is pinned by a test.

Pacman mirrors: `Server = <base>/$arch/$repo`. The `$arch` and `$repo` are
pacman's own variables and must survive into the file unexpanded.

## Custom mirrors

The installer accepts a pasted URL. Trailing slashes and a trailing `/os` are
both tolerated, since those are the two shapes people paste. A custom mirror is
treated as pinned.

## Checksums

Arch Linux ARM does not publish per-image checksums alongside the `-latest`
tarballs, so Myarch cannot verify a signature it was never given. What it does
instead:

- The downloader computes a SHA-256 of everything it fetched and logs it, and
  verifies against an expected value when one is supplied.
- A transfer that ends short of the declared length is a failure, not a success.
- The extractor refuses an archive that yields implausibly few files, which is
  what a truncated download or a captive portal's HTML error page looks like.
- Compression is detected from magic bytes, not the file name, so an HTML page
  named `.tar.gz` is reported as such rather than failing deep inside the
  extractor.

Package integrity *is* properly verified: pacman checks signatures against the
Arch Linux ARM keyring, which `first-boot.sh` initialises.
