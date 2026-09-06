#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check-release-repository.py"
GROUP_PATH = Path("com/github/dmytromitin")
VERSION = "0.1.0"
LANES = ("3.3.8", "3.8.4", "3.9.0")
ANNOTATION = "allow-experimental-annotation_3"
PLUGINS = tuple(f"allow-experimental-plugin_{lane}" for lane in LANES)
MODULES = (ANNOTATION, *PLUGINS)
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")


def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    value.update(path.read_bytes())
    return value.hexdigest()


def write_checksums(primary: Path) -> None:
    for algorithm in CHECKSUMS:
        primary.with_name(primary.name + f".{algorithm}").write_text(
            digest(primary, algorithm) + "\n", encoding="ascii"
        )


def replace_zip_entry(archive_path: Path, entry_name: str, replacement: bytes) -> None:
    with zipfile.ZipFile(archive_path) as archive:
        entries = {
            item.filename: archive.read(item)
            for item in archive.infolist()
            if item.filename != entry_name
        }
    with zipfile.ZipFile(archive_path, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
        archive.writestr(entry_name, replacement)


class ReleaseRepositoryCheckerTest(unittest.TestCase):
    def fixture(self, root: Path) -> Path:
        project = root / "project"
        repository = root / "repository"
        project.mkdir()
        license_bytes = b"Apache fixture\n"
        (project / "LICENSE").write_bytes(license_bytes)
        for module in MODULES:
            directory = repository / GROUP_PATH / module / VERSION
            directory.mkdir(parents=True)
            base = f"{module}-{VERSION}"
            lane = module.removeprefix("allow-experimental-plugin_") if module in PLUGINS else None
            dependency = (
                "<dependency><groupId>org.scala-lang</groupId>"
                "<artifactId>scala3-library_3</artifactId><version>3.3.8</version></dependency>"
                if lane is None
                else "<dependency><groupId>org.scala-lang</groupId>"
                f"<artifactId>scala3-compiler_3</artifactId><version>{lane}</version>"
                "<scope>provided</scope></dependency>"
            )
            pom = directory / f"{base}.pom"
            pom.write_text(
                f"""<project><modelVersion>4.0.0</modelVersion>
  <groupId>com.github.dmytromitin</groupId><artifactId>{module}</artifactId><version>{VERSION}</version>
  <name>{module}</name><description>Implementation-scoped access to selected experimental Scala 3 APIs.</description>
  <url>https://github.com/DmytroMitin/allow-experimental</url>
  <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url><distribution>repo</distribution></license></licenses>
  <scm><url>https://github.com/DmytroMitin/allow-experimental</url><connection>scm:git:https://github.com/DmytroMitin/allow-experimental.git</connection></scm>
  <developers><developer><id>dmytromitin</id><name>Dmytro Mitin</name><url>https://github.com/DmytroMitin</url></developer></developers>
  <dependencies>{dependency}</dependencies>
</project>
""",
                encoding="utf-8",
            )
            jar_paths = [
                directory / f"{base}.jar",
                directory / f"{base}-sources.jar",
                directory / f"{base}-javadoc.jar",
            ]
            for index, jar in enumerate(jar_paths):
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr("META-INF/LICENSE", license_bytes)
                    if index == 0 and lane is None:
                        archive.writestr(
                            "META-INF/MANIFEST.MF",
                            "Manifest-Version: 1.0\nAllow-Experimental-Scala-Version: 3.3.8\n",
                        )
                        archive.writestr(
                            "io/github/dmytromitin/allowexperimental/allowExperimental.class",
                            b"annotation",
                        )
                    elif index == 0:
                        archive.writestr(
                            "META-INF/MANIFEST.MF",
                            f"Manifest-Version: 1.0\nAllow-Experimental-Scala-Version: {lane}\n",
                        )
                        archive.writestr("plugin.properties", b"pluginClass=io.github.dmytromitin.allowexperimental.plugin.AllowExperimentalPlugin\n")
                        archive.writestr(
                            "io/github/dmytromitin/allowexperimental/plugin/ExactCompilerVersion.class",
                            b"forwarding class without the constant",
                        )
                        archive.writestr(
                            "io/github/dmytromitin/allowexperimental/plugin/ExactCompilerVersion$.class",
                            f"module class exact compiler {lane}".encode("ascii"),
                        )
                    else:
                        archive.writestr(f"fixture-{index}.txt", module.encode("ascii"))
            for primary in (pom, *jar_paths):
                write_checksums(primary)
        return repository

    def run_checker(self, project: Path, repository: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(CHECKER),
                str(project),
                str(repository),
                "--source-identity",
                "a38dcd207c26ef8eb3cbf8d4e96b4bd908653173",
                "--json",
                str(repository.parent / "manifest.json"),
                "--markdown",
                str(repository.parent / "manifest.md"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_exact_candidate_is_accepted_with_deterministic_16_primary_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            first = self.run_checker(root / "project", repository)
            self.assertEqual(first.returncode, 0, first.stdout)
            first_bytes = (root / "manifest.json").read_bytes()
            second = self.run_checker(root / "project", repository)
            self.assertEqual(second.returncode, 0, second.stdout)
            self.assertEqual((root / "manifest.json").read_bytes(), first_bytes)
            manifest = json.loads(first_bytes)
            self.assertEqual(manifest["schema"], "allow-experimental-release-candidate-manifest-v1")
            self.assertEqual(manifest["source_identity"], "a38dcd207c26ef8eb3cbf8d4e96b4bd908653173")
            self.assertEqual(manifest["candidate_version"], VERSION)
            self.assertEqual(len(manifest["coordinates"]), 4)
            self.assertEqual(sum(len(item["files"]) for item in manifest["coordinates"]), 16)
            self.assertEqual(len(manifest["primary_manifest_sha256"]), 64)
            self.assertNotIn("/home/", first_bytes.decode("utf-8"))
            self.assertNotIn("/tmp/", first_bytes.decode("utf-8"))

    def test_missing_primary_and_extra_coordinate_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            first_module = MODULES[0]
            (repository / GROUP_PATH / first_module / VERSION / f"{first_module}-{VERSION}-javadoc.jar").unlink()
            (repository / GROUP_PATH / "unexpected_3" / VERSION).mkdir(parents=True)
            unexpected_path = repository / "org/example/unexpected/0.1.0/unexpected-0.1.0.pom"
            unexpected_path.parent.mkdir(parents=True)
            unexpected_path.write_text("unexpected", encoding="utf-8")
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn("DEPLOYABLE_MISSING", result.stdout)
            self.assertIn("COORDINATE_UNEXPECTED:unexpected_3", result.stdout)
            self.assertIn("PATH_UNEXPECTED:org/example/unexpected/0.1.0/unexpected-0.1.0.pom", result.stdout)

    def test_snapshot_residue_and_unexpected_file_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            module = MODULES[0]
            directory = repository / GROUP_PATH / module / VERSION
            pom = directory / f"{module}-{VERSION}.pom"
            pom.write_text(pom.read_text(encoding="utf-8") + "<!-- 0.1.0-SNAPSHOT -->\n", encoding="utf-8")
            write_checksums(pom)
            (directory / "maven-metadata.xml").write_text("metadata", encoding="utf-8")
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn("SNAPSHOT_RESIDUE", result.stdout)
            self.assertIn("FILE_UNEXPECTED", result.stdout)

    def test_wrong_compiler_identity_and_nonprovided_scope_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            module = PLUGINS[0]
            directory = repository / GROUP_PATH / module / VERSION
            pom = directory / f"{module}-{VERSION}.pom"
            pom.write_text(pom.read_text(encoding="utf-8").replace("<scope>provided</scope>", "<scope>compile</scope>"), encoding="utf-8")
            write_checksums(pom)
            jar = directory / f"{module}-{VERSION}.jar"
            replace_zip_entry(
                jar,
                "META-INF/MANIFEST.MF",
                b"Manifest-Version: 1.0\nAllow-Experimental-Scala-Version: 3.8.4\n",
            )
            write_checksums(jar)
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn("POM_COMPILER_DEPENDENCY_INVALID", result.stdout)
            self.assertIn("COMPILER_IDENTITY_INVALID", result.stdout)

    def test_license_repository_metadata_and_archive_leakage_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = self.fixture(root)
            annotation_dir = repository / GROUP_PATH / ANNOTATION / VERSION
            pom = annotation_dir / f"{ANNOTATION}-{VERSION}.pom"
            pom.write_text(pom.read_text(encoding="utf-8").replace("</project>", "<repositories/><distributionManagement/></project>"), encoding="utf-8")
            write_checksums(pom)
            jar = annotation_dir / f"{ANNOTATION}-{VERSION}.jar"
            replace_zip_entry(jar, "META-INF/LICENSE", b"wrong license")
            with zipfile.ZipFile(jar, "a") as archive:
                archive.writestr("dotty/tools/dotc/Compiler.class", b"leak")
            write_checksums(jar)
            result = self.run_checker(root / "project", repository)
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn("POM_FORBIDDEN_REPOSITORY_METADATA", result.stdout)
            self.assertIn("JAR_LICENSE_INVALID", result.stdout)
            self.assertIn("ARCHIVE_CONTENT_FORBIDDEN", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
