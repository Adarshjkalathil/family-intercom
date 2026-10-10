#!/usr/bin/env python3
"""
Check that the risky dependency versions actually exist, before a build spends
five minutes finding out.

Two artifacts in libs.versions.toml were pinned without access to any Maven
repository, so their versions are educated guesses. This asks the repositories
what really exists and tells you exactly what to put in the file.

It downloads nothing and needs no Android toolchain - a few HTTPS requests and
the Python standard library. Run it anywhere, including on a machine with no
disk space to spare:

    python3 tools/check-deps.py

    --self-test   verify the script's own logic offline, no network
"""
import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

TOML = Path(__file__).resolve().parent.parent / "android/gradle/libs.versions.toml"
TIMEOUT = 20

# key in [versions], group:artifact, and where to look for it
ARTIFACTS = [
    {
        "key": "webrtc",
        "group": "io.github.webrtc-sdk",
        "artifact": "android",
        "repos": ["central"],
        "note": "WebRTC. Releases: https://github.com/webrtc-sdk/android/releases",
    },
    {
        "key": "uvc",
        "group": "com.herohan",
        "artifact": "UVCAndroid",
        "repos": ["central", "jitpack"],
        "note": "USB webcam capture. Fallback coordinate: com.github.shiyinghan:UVCAndroid (jitpack)",
    },
    # These are well-known and almost certainly fine, but checking them costs
    # nothing and rules them out when something else goes wrong.
    {"key": "agp", "group": "com.android.tools.build", "artifact": "gradle", "repos": ["google"]},
    {"key": "okhttp", "group": "com.squareup.okhttp3", "artifact": "okhttp", "repos": ["central"]},
    {"key": "firebaseBom", "group": "com.google.firebase", "artifact": "firebase-bom", "repos": ["google"]},
]

REPOS = {
    "central": "https://repo1.maven.org/maven2",
    "google": "https://dl.google.com/dl/android/maven2",
    "jitpack": "https://jitpack.io",
}


# ---------------------------------------------------------------------------
# Pure logic, covered by --self-test
# ---------------------------------------------------------------------------

def pom_url(repo_base, group, artifact, version):
    """Maven's layout: group dots become slashes."""
    return f"{repo_base}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"


def metadata_url(repo_base, group, artifact):
    return f"{repo_base}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"


def parse_metadata_versions(xml_text):
    """Pull <version> entries out of maven-metadata.xml, newest last."""
    return re.findall(r"<version>([^<]+)</version>", xml_text)


def version_key(version):
    """
    Sort versions numerically where possible so 125.6422.7 beats 125.6422.04
    and 1.0.10 beats 1.0.9. Non-numeric parts sort last.
    """
    parts = re.split(r"[.\-_]", version)
    key = []
    for p in parts:
        key.append((0, int(p)) if p.isdigit() else (1, 0))
    return key


def newest(versions, stable_only=True):
    """Pick the highest version, skipping pre-releases unless there is nothing else."""
    candidates = versions
    if stable_only:
        stable = [v for v in versions
                  if not re.search(r"(alpha|beta|rc|dev|snapshot)", v, re.I)]
        candidates = stable or versions
    return max(candidates, key=version_key) if candidates else None


def read_pinned_versions(toml_text):
    """Read the [versions] block of the catalog."""
    out = {}
    in_versions = False
    for line in toml_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            in_versions = stripped == "[versions]"
            continue
        if not in_versions or not stripped or stripped.startswith("#"):
            continue
        m = re.match(r'([A-Za-z0-9_\-]+)\s*=\s*"([^"]+)"', stripped)
        if m:
            out[m.group(1)] = m.group(2)
    return out


# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "intercom-dep-check"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return r.read().decode("utf-8", "replace")


def exists(url):
    req = urllib.request.Request(url, method="HEAD",
                                 headers={"User-Agent": "intercom-dep-check"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return 200 <= r.status < 300
    except urllib.error.HTTPError:
        return False
    except Exception:
        return False


def available_versions(group, artifact, repos):
    """Ask each repository what versions it has. Returns (versions, repo_name)."""
    for repo in repos:
        base = REPOS[repo]
        try:
            xml = fetch(metadata_url(base, group, artifact))
            versions = parse_metadata_versions(xml)
            if versions:
                return versions, repo
        except Exception:
            continue
    return [], None


# ---------------------------------------------------------------------------

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true",
                        help="check the script's own logic without network access")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not TOML.exists():
        print(f"Cannot find {TOML}")
        return 2

    pinned = read_pinned_versions(TOML.read_text(encoding="utf-8"))
    print(f"\nChecking {len(ARTIFACTS)} dependencies against their repositories\n")

    problems = []
    for spec in ARTIFACTS:
        key, group, artifact = spec["key"], spec["group"], spec["artifact"]
        want = pinned.get(key)
        label = f"{group}:{artifact}"

        if want is None:
            print(f"  {YELLOW}?{RESET}  {label}  (no '{key}' in [versions])")
            continue

        versions, repo = available_versions(group, artifact, spec["repos"])

        if not versions:
            # Metadata can be absent even when the artifact is there.
            found = any(exists(pom_url(REPOS[r], group, artifact, want)) for r in spec["repos"])
            if found:
                print(f"  {GREEN}ok{RESET} {label}:{want}  {DIM}(exists; no version listing){RESET}")
            else:
                print(f"  {RED}FAIL{RESET} {label}:{want}  could not be found in "
                      f"{', '.join(spec['repos'])}")
                problems.append((spec, want, []))
            continue

        if want in versions:
            latest = newest(versions)
            suffix = "" if latest == want else f"  {DIM}(latest is {latest}){RESET}"
            print(f"  {GREEN}ok{RESET} {label}:{want}  {DIM}via {repo}{RESET}{suffix}")
        else:
            print(f"  {RED}FAIL{RESET} {label}:{want}  does not exist in {repo}")
            problems.append((spec, want, versions))

    if not problems:
        print(f"\n{GREEN}All pinned versions exist. The build will not fail on resolution.{RESET}\n")
        return 0

    print(f"\n{RED}{len(problems)} version(s) need changing in "
          f"android/gradle/libs.versions.toml:{RESET}\n")

    for spec, want, versions in problems:
        suggestion = newest(versions) if versions else None
        print(f"  {spec['group']}:{spec['artifact']}")
        print(f"    currently: {spec['key']} = \"{want}\"")
        if suggestion:
            print(f"    {GREEN}change to: {spec['key']} = \"{suggestion}\"{RESET}")
            recent = sorted(versions, key=version_key)[-8:]
            print(f"    {DIM}recent: {', '.join(reversed(recent))}{RESET}")
        else:
            print(f"    {YELLOW}no versions found at all - the coordinate itself may be wrong{RESET}")
        if spec.get("note"):
            print(f"    {DIM}{spec['note']}{RESET}")
        print()

    return 1


# ---------------------------------------------------------------------------

def self_test():
    """Offline checks of the parsing and sorting, so the logic is not itself a guess."""
    failures = []

    def check(label, actual, expected):
        if actual != expected:
            failures.append(f"{label}: got {actual!r}, wanted {expected!r}")

    check(
        "pom url",
        pom_url("https://repo1.maven.org/maven2", "io.github.webrtc-sdk", "android", "125.6422.07"),
        "https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/125.6422.07/android-125.6422.07.pom",
    )
    check(
        "metadata url",
        metadata_url("https://jitpack.io", "com.herohan", "UVCAndroid"),
        "https://jitpack.io/com/herohan/UVCAndroid/maven-metadata.xml",
    )
    check(
        "metadata parsing",
        parse_metadata_versions(
            "<metadata><versioning><versions>"
            "<version>1.0.5</version><version>1.0.7</version>"
            "</versions></versioning></metadata>"
        ),
        ["1.0.5", "1.0.7"],
    )
    # Numeric sorting, not lexicographic: 1.0.10 must beat 1.0.9.
    check("numeric sort", newest(["1.0.9", "1.0.10", "1.0.2"]), "1.0.10")
    check("zero padding", newest(["125.6422.04", "125.6422.7"]), "125.6422.7")
    check("prereleases skipped", newest(["1.0.7", "1.1.0-beta01"]), "1.0.7")
    check("prerelease used if only option", newest(["1.1.0-beta01"]), "1.1.0-beta01")
    check("empty", newest([]), None)

    catalog = read_pinned_versions(
        '[versions]\n'
        '# a comment\n'
        'webrtc = "125.6422.07"\n'
        'uvc = "1.0.7"\n'
        '\n'
        '[libraries]\n'
        'webrtc = { module = "io.github.webrtc-sdk:android", version.ref = "webrtc" }\n'
    )
    check("catalog: reads versions", catalog.get("webrtc"), "125.6422.07")
    check("catalog: reads second", catalog.get("uvc"), "1.0.7")
    check("catalog: stops at [libraries]", "module" in str(catalog.get("webrtc")), False)
    check("catalog: size", len(catalog), 2)

    if failures:
        print("\nself-test FAILED\n")
        for f in failures:
            print(f"  {f}")
        print()
        return 1
    print("\nself-test passed (13 checks)\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
