#!/usr/bin/env python3
import argparse
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
FDROID_FILE = ROOT / ".fdroid.yml"
SEMVER_PATTERN = r"\d+\.\d+\.\d+"


def replace_once(content: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, content, count=1, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match for {label}, got {count}")
    return updated


def extract_once(content: str, pattern: str, label: str) -> str:
    match = re.search(pattern, content, flags=re.MULTILINE)
    if match is None:
        raise RuntimeError(f"Unable to find {label}")
    return match.group(1)


def read_gradle_versions() -> tuple[str, int]:
    content = GRADLE_FILE.read_text(encoding="utf-8")
    version_name = extract_once(
        content,
        rf'^\s*versionName\s*=\s*"({SEMVER_PATTERN})"\s*$',
        "gradle versionName",
    )
    version_code_str = extract_once(
        content,
        r"^\s*versionCode\s*=\s*(\d+)\s*$",
        "gradle versionCode",
    )
    return version_name, int(version_code_str)


def read_fdroid_versions() -> tuple[str, int]:
    content = FDROID_FILE.read_text(encoding="utf-8")
    version_name = extract_once(
        content,
        rf"^CurrentVersion:\s+({SEMVER_PATTERN})\s*$",
        ".fdroid CurrentVersion",
    )
    version_code_str = extract_once(
        content,
        r"^CurrentVersionCode:\s+(\d+)\s*$",
        ".fdroid CurrentVersionCode",
    )
    return version_name, int(version_code_str)


def bump_version(version_name: str, bump: str) -> str:
    major, minor, patch = [int(part) for part in version_name.split(".")]
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def resolve_next_versions(
    requested_version_name: str | None,
    requested_version_code: int | None,
    bump: str,
) -> tuple[str, int]:
    gradle_version_name, gradle_version_code = read_gradle_versions()
    fdroid_version_name, fdroid_version_code = read_fdroid_versions()

    if gradle_version_name != fdroid_version_name:
        raise RuntimeError(
            "Version mismatch between app/build.gradle.kts and .fdroid.yml "
            f"({gradle_version_name} != {fdroid_version_name})"
        )

    if gradle_version_code != fdroid_version_code:
        raise RuntimeError(
            "VersionCode mismatch between app/build.gradle.kts and .fdroid.yml "
            f"({gradle_version_code} != {fdroid_version_code})"
        )

    version_name = requested_version_name or bump_version(gradle_version_name, bump)
    version_code = requested_version_code if requested_version_code is not None else gradle_version_code + 1
    return version_name, version_code


def update_gradle(version_name: str, version_code: int) -> None:
    content = GRADLE_FILE.read_text(encoding="utf-8")
    content = replace_once(
        content,
        r"^\s*versionCode\s*=\s*\d+\s*$",
        f"        versionCode = {version_code}",
        "gradle versionCode",
    )
    content = replace_once(
        content,
        r'^\s*versionName\s*=\s*"[^"]+"\s*$',
        f'        versionName = "{version_name}"',
        "gradle versionName",
    )
    GRADLE_FILE.write_text(content, encoding="utf-8")


def update_fdroid(version_name: str, version_code: int) -> None:
    tag = f"v{version_name}"
    content = FDROID_FILE.read_text(encoding="utf-8")
    content = replace_once(
        content,
        r"^\s*-\s+versionName:\s+.+$",
        f"  - versionName: {version_name}",
        ".fdroid Builds.versionName",
    )
    content = replace_once(
        content,
        r"^\s+versionCode:\s+\d+\s*$",
        f"    versionCode: {version_code}",
        ".fdroid Builds.versionCode",
    )
    content = replace_once(
        content,
        r"^\s+commit:\s+.+$",
        f"    commit: {tag}",
        ".fdroid Builds.commit",
    )
    content = replace_once(
        content,
        r"^CurrentVersion:\s+.+$",
        f"CurrentVersion: {version_name}",
        ".fdroid CurrentVersion",
    )
    content = replace_once(
        content,
        r"^CurrentVersionCode:\s+\d+\s*$",
        f"CurrentVersionCode: {version_code}",
        ".fdroid CurrentVersionCode",
    )
    FDROID_FILE.write_text(content, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare release version files")
    parser.add_argument(
        "--version-name",
        help="Target version name (optional). If omitted, increments current semantic version.",
    )
    parser.add_argument(
        "--version-code",
        type=int,
        help="Target version code (optional). If omitted, increments current versionCode by 1.",
    )
    parser.add_argument(
        "--bump",
        choices=["patch", "minor", "major"],
        default="patch",
        help="Version bump type used when --version-name is omitted (default: patch).",
    )
    parser.add_argument(
        "--github-output",
        help="Optional file path for GitHub Actions outputs.",
    )
    return parser.parse_args()


def write_github_outputs(output_file: str, version_name: str, version_code: int) -> None:
    tag = f"v{version_name}"
    with pathlib.Path(output_file).open("a", encoding="utf-8") as handle:
        handle.write(f"version_name={version_name}\n")
        handle.write(f"version_code={version_code}\n")
        handle.write(f"tag={tag}\n")


def main() -> int:
    args = parse_args()

    version_name, version_code = resolve_next_versions(
        requested_version_name=args.version_name,
        requested_version_code=args.version_code,
        bump=args.bump,
    )

    if version_code <= 0:
        raise RuntimeError("version-code must be positive")

    if not re.fullmatch(SEMVER_PATTERN, version_name):
        raise RuntimeError("version-name must match semantic style like 0.1.7")

    update_gradle(version_name, version_code)
    update_fdroid(version_name, version_code)

    if args.github_output:
        write_github_outputs(args.github_output, version_name, version_code)

    print(f"Prepared release files for v{version_name} ({version_code})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
