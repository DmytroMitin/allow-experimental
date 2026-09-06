#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


GROUP = "com.github.dmytromitin"
GROUP_PATH = Path("com/github/dmytromitin")
VERSION = "0.1.0"
SCALA_VERSIONS = ("3.3.8", "3.8.4", "3.9.0")
ANNOTATION = "allow-experimental-annotation_3"
PLUGINS = tuple(f"allow-experimental-plugin_{version}" for version in SCALA_VERSIONS)
MODULES = (ANNOTATION, *PLUGINS)
MODULE_SCALA_VERSION = {module: version for module, version in zip(PLUGINS, SCALA_VERSIONS)}
CLASSIFIERS = ("", "-sources", "-javadoc")
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
EXPECTED_PRIMARY_COUNT = 16
PROJECT_URL = "https://github.com/DmytroMitin/allow-experimental"
LICENSE_NAME = "Apache-2.0"
LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
PASS = "LOCAL_RELEASE_REPOSITORY_CHECK_PASS"
BLOCKED = "LOCAL_RELEASE_REPOSITORY_CHECK_BLOCKED"


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def children(element: ET.Element | None, name: str) -> list[ET.Element]:
    if element is None:
        return []
    return [child for child in element if child.tag.rsplit("}", 1)[-1] == name]


def one(element: ET.Element | None, name: str) -> ET.Element | None:
    values = children(element, name)
    return values[0] if len(values) == 1 else None


def text(element: ET.Element | None, name: str) -> str:
    value = one(element, name)
    return (value.text or "").strip() if value is not None else ""


def pom_dependencies(path: Path, module: str, errors: list[str]) -> list[dict[str, str]]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        errors.append(f"POM_INVALID:{module}:{error}")
        return []

    for field, expected in (("groupId", GROUP), ("artifactId", module), ("version", VERSION)):
        if text(root, field) != expected:
            errors.append(f"POM_IDENTITY_INVALID:{module}:{field}")
    for field in ("name", "description"):
        if not text(root, field):
            errors.append(f"POM_METADATA_MISSING:{module}:{field}")
    if text(root, "url") != PROJECT_URL:
        errors.append(f"POM_PROJECT_URL_INVALID:{module}")

    licenses = children(one(root, "licenses"), "license")
    if not (
        len(licenses) == 1
        and text(licenses[0], "name") == LICENSE_NAME
        and text(licenses[0], "url") == LICENSE_URL
        and text(licenses[0], "distribution") == "repo"
    ):
        errors.append(f"POM_LICENSE_INVALID:{module}")
    scm = one(root, "scm")
    if scm is None or text(scm, "url") != PROJECT_URL or not text(scm, "connection").startswith("scm:git:"):
        errors.append(f"POM_SCM_INVALID:{module}")
    developers = children(one(root, "developers"), "developer")
    if len(developers) != 1 or any(not text(developers[0], field) for field in ("id", "name", "url")):
        errors.append(f"POM_DEVELOPER_INVALID:{module}")
    if children(root, "repositories") or children(root, "distributionManagement"):
        errors.append(f"POM_FORBIDDEN_REPOSITORY_METADATA:{module}")

    rendered = path.read_text(encoding="utf-8", errors="replace")
    if "SNAPSHOT" in rendered:
        errors.append(f"SNAPSHOT_RESIDUE:{module}:pom")
    if "/home/" in rendered or "/tmp/" in rendered:
        errors.append(f"POM_PRIVATE_PATH_LEAK:{module}")

    dependencies: list[dict[str, str]] = []
    for dependency in children(one(root, "dependencies"), "dependency"):
        dependencies.append(
            {
                "group": text(dependency, "groupId"),
                "artifact": text(dependency, "artifactId"),
                "version": text(dependency, "version"),
                "scope": text(dependency, "scope") or "compile",
            }
        )
    if module in MODULE_SCALA_VERSION:
        lane = MODULE_SCALA_VERSION[module]
        matching = [
            item
            for item in dependencies
            if item["group"] == "org.scala-lang"
            and item["artifact"] == "scala3-compiler_3"
            and item["version"] == lane
            and item["scope"] == "provided"
        ]
        if len(matching) != 1:
            errors.append(f"POM_COMPILER_DEPENDENCY_INVALID:{module}")
    elif any(item["artifact"].startswith("scala3-compiler") for item in dependencies):
        errors.append(f"POM_ANNOTATION_COMPILER_DEPENDENCY_FORBIDDEN:{module}")
    return sorted(dependencies, key=lambda item: (item["scope"], item["group"], item["artifact"], item["version"]))


def manifest_attribute(archive: zipfile.ZipFile, name: str) -> str:
    rendered = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace").replace("\r", "")
    prefix = name + ": "
    return next((line[len(prefix) :] for line in rendered.splitlines() if line.startswith(prefix)), "")


def inspect_archive(path: Path, module: str, license_bytes: bytes, errors: list[str]) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if not names:
                errors.append(f"ARCHIVE_EMPTY:{module}:{path.name}")
                return
            if len(names) != len(set(names)):
                errors.append(f"ARCHIVE_DUPLICATE_ENTRY:{module}:{path.name}")
            try:
                if archive.read("META-INF/LICENSE") != license_bytes:
                    errors.append(f"JAR_LICENSE_INVALID:{module}:{path.name}")
            except KeyError:
                errors.append(f"JAR_LICENSE_INVALID:{module}:{path.name}")
            forbidden = (
                "dotty/tools/dotc/",
                "m0-fixtures",
                "m1-fixtures",
                "m3-fixtures",
                "Verifier",
                "macroparadise",
                "quasiquotes",
                "auxify",
            )
            for entry in names:
                if any(token.lower() in entry.lower() for token in forbidden):
                    errors.append(f"ARCHIVE_CONTENT_FORBIDDEN:{module}:{path.name}:{entry}")

            base = f"{module}-{VERSION}"
            if path.name == f"{base}.jar":
                expected_lane = "3.3.8" if module == ANNOTATION else MODULE_SCALA_VERSION[module]
                try:
                    actual_lane = manifest_attribute(archive, "Allow-Experimental-Scala-Version")
                except KeyError:
                    actual_lane = ""
                if actual_lane != expected_lane:
                    errors.append(f"COMPILER_IDENTITY_INVALID:{module}:manifest")
                if module == ANNOTATION:
                    expected_class = "io/github/dmytromitin/allowexperimental/allowExperimental.class"
                    if expected_class not in names:
                        errors.append(f"ANNOTATION_CLASS_MISSING:{module}")
                    if "plugin.properties" in names:
                        errors.append(f"ANNOTATION_PLUGIN_DESCRIPTOR_FORBIDDEN:{module}")
                else:
                    exact_class = "io/github/dmytromitin/allowexperimental/plugin/ExactCompilerVersion$.class"
                    if "plugin.properties" not in names:
                        errors.append(f"PLUGIN_DESCRIPTOR_MISSING:{module}")
                    if exact_class not in names or expected_lane.encode("ascii") not in archive.read(exact_class):
                        errors.append(f"COMPILER_IDENTITY_INVALID:{module}:class")
    except (OSError, zipfile.BadZipFile):
        errors.append(f"ARCHIVE_INVALID:{module}:{path.name}")


def check(project: Path, repository: Path, source_identity: str) -> tuple[dict[str, object], list[str]]:
    errors: list[str] = []
    expected_repository_files = {
        GROUP_PATH / module / VERSION / name
        for module in MODULES
        for primary in (
            f"{module}-{VERSION}.pom",
            f"{module}-{VERSION}.jar",
            f"{module}-{VERSION}-sources.jar",
            f"{module}-{VERSION}-javadoc.jar",
        )
        for name in (primary, *(primary + f".{algorithm}" for algorithm in CHECKSUMS))
    }
    actual_repository_files = {
        path.relative_to(repository)
        for path in repository.rglob("*")
        if path.is_file()
    }
    for relative in sorted(actual_repository_files - expected_repository_files):
        errors.append(f"PATH_UNEXPECTED:{relative.as_posix()}")
    group_root = repository / GROUP_PATH
    actual_modules = {path.name for path in group_root.iterdir() if path.is_dir()} if group_root.is_dir() else set()
    for module in sorted(set(MODULES) - actual_modules):
        errors.append(f"COORDINATE_MISSING:{module}")
    for module in sorted(actual_modules - set(MODULES)):
        errors.append(f"COORDINATE_UNEXPECTED:{module}")
    if (repository / "io/github/dmytromitin").exists():
        errors.append("LEGACY_NAMESPACE_PRESENT")

    license_bytes = (project / "LICENSE").read_bytes()
    coordinates: list[dict[str, object]] = []
    for module in MODULES:
        directory = group_root / module / VERSION
        base = f"{module}-{VERSION}"
        primaries = [directory / f"{base}.pom"] + [directory / f"{base}{classifier}.jar" for classifier in CLASSIFIERS]
        expected_files = {
            name
            for primary in primaries
            for name in (primary.name, *(primary.name + f".{algorithm}" for algorithm in CHECKSUMS))
        }
        files: list[dict[str, object]] = []
        for primary in primaries:
            if not primary.is_file() or primary.stat().st_size == 0:
                errors.append(f"DEPLOYABLE_MISSING:{module}:{primary.name}")
                continue
            if "SNAPSHOT" in primary.name:
                errors.append(f"SNAPSHOT_RESIDUE:{module}:{primary.name}")
            if primary.with_name(primary.name + ".asc").exists():
                errors.append(f"UNEXPECTED_SIGNATURE:{module}:{primary.name}.asc")
            for algorithm in CHECKSUMS:
                checksum = primary.with_name(primary.name + f".{algorithm}")
                if not checksum.is_file() or checksum.read_text(encoding="ascii").strip().lower() != digest(primary, algorithm):
                    errors.append(f"CHECKSUM_INVALID:{module}:{primary.name}:{algorithm}")
            if primary.suffix == ".jar":
                inspect_archive(primary, module, license_bytes, errors)
            files.append(
                {
                    "relative_path": primary.relative_to(repository).as_posix(),
                    "filename": primary.name,
                    "size": primary.stat().st_size,
                    "sha256": digest(primary, "sha256"),
                    "sha512": digest(primary, "sha512"),
                    "checksums": {algorithm: digest(primary, algorithm) for algorithm in CHECKSUMS},
                }
            )
        if directory.is_dir():
            for extra in sorted(path.name for path in directory.iterdir() if path.is_file() and path.name not in expected_files):
                errors.append(f"FILE_UNEXPECTED:{module}:{extra}")
        pom = directory / f"{base}.pom"
        dependencies = pom_dependencies(pom, module, errors) if pom.is_file() else []
        coordinates.append(
            {
                "coordinate": f"{GROUP}:{module}:{VERSION}",
                "scala_compiler_line": "binary-cross-built-on-3.3.8" if module == ANNOTATION else MODULE_SCALA_VERSION[module],
                "files": files,
                "pom_dependencies": dependencies,
            }
        )

    records = sorted(
        (
            {
                "relative_path": item["relative_path"],
                "size": item["size"],
                "sha256": item["sha256"],
                "sha512": item["sha512"],
            }
            for coordinate in coordinates
            for item in coordinate["files"]  # type: ignore[index]
        ),
        key=lambda item: str(item["relative_path"]),
    )
    if len(records) != EXPECTED_PRIMARY_COUNT:
        errors.append(f"PRIMARY_FILE_COUNT_INVALID:expected-{EXPECTED_PRIMARY_COUNT}:actual-{len(records)}")
    rendered_records = json.dumps(records, sort_keys=True, separators=(",", ":")).encode("utf-8")
    manifest: dict[str, object] = {
        "schema": "allow-experimental-release-candidate-manifest-v1",
        "source_identity": source_identity,
        "source": {"identity": source_identity, "status": "PREPARED_WORKTREE_AWAITING_PUBLICATION_COMMIT"},
        "candidate_version": VERSION,
        "release_contract": {
            "organization": GROUP,
            "version": VERSION,
            "future_tag_if_separately_authorized": "v0.1.0",
            "scala_full_versions": list(SCALA_VERSIONS),
            "publication_allowlist": list(MODULES),
            "primary_file_count": EXPECTED_PRIMARY_COUNT,
            "sbt_plugin": False,
        },
        "license": {"name": LICENSE_NAME, "url": LICENSE_URL, "distribution": "repo"},
        "coordinates": coordinates,
        "primary_manifest_sha256": hashlib.sha256(rendered_records).hexdigest(),
        "signing": {"status": "EPHEMERAL_TEST_SIGNING_NOT_YET_PERFORMED", "real_release_signing_key_used": False},
        "remote_state": "NOT_UPLOADED_NOT_PUBLISHED_NO_TAG_NO_GITHUB_RELEASE",
        "assertions": {
            "exact_coordinate_set": not any(error.startswith("COORDINATE_") for error in errors),
            "non_snapshot": not any(error.startswith("SNAPSHOT_") for error in errors),
            "all_checksums_verified": not any(error.startswith("CHECKSUM_") for error in errors),
            "no_remote_action": True,
        },
    }
    return manifest, sorted(set(errors))


def markdown(manifest: dict[str, object]) -> str:
    lines = [
        "# Local `0.1.0` release-candidate manifest",
        "",
        f"Source: `{manifest['source_identity']}`",
        "Classification: `LOCAL_UNSIGNED_CANDIDATE_FOR_EPHEMERAL_TEST_SIGNING_ONLY`",
        "",
    ]
    for coordinate in manifest["coordinates"]:  # type: ignore[index]
        lines.extend((f"## {coordinate['coordinate']}", "", "| File | Size | SHA-256 |", "|---|---:|---|"))
        for item in coordinate["files"]:
            lines.append(f"| `{item['filename']}` | {item['size']} | `{item['sha256']}` |")
        lines.append("")
    lines.extend(
        (
            f"Primary manifest SHA-256: `{manifest['primary_manifest_sha256']}`",
            "",
            "No Central credential, upload, publication, tag, GitHub Release, or remote artifact action was performed.",
            "",
        )
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("project", type=Path)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--source-identity", required=True)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest, errors = check(args.project.resolve(), args.repository.resolve(), args.source_identity)
    except (OSError, UnicodeError) as error:
        print(f"CHECKER_INPUT_INVALID:{error}", file=sys.stderr)
        print(BLOCKED, file=sys.stderr)
        return 3
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print(BLOCKED, file=sys.stderr)
        return 3
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.markdown.write_text(markdown(manifest), encoding="utf-8")
    print(PASS)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
