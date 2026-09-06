#!/usr/bin/env python3
from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0"
GROUP = "com.github.dmytromitin"
SCALA_VERSIONS = ("3.3.8", "3.8.4", "3.9.0")


def project_section(build: str, project_id: str) -> str:
    start = build.index(f"lazy val {project_id} =")
    end = build.find("\nlazy val ", start + 1)
    return build[start:] if end < 0 else build[start:end]


class ReleaseConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.build = (ROOT / "build.sbt").read_text(encoding="utf-8")
        self.readme = (ROOT / "README.md").read_text(encoding="utf-8")

    def test_release_identity_and_publication_topology_are_exact(self) -> None:
        self.assertIn(f'ThisBuild / organization := "{GROUP}"', self.build)
        self.assertIn(f'ThisBuild / version := "{VERSION}"', self.build)
        self.assertIn('ThisBuild / versionScheme := Some("early-semver")', self.build)
        self.assertIn('ThisBuild / publishMavenStyle := true', self.build)
        self.assertIn('ThisBuild / pomIncludeRepository := (_ => false)', self.build)
        self.assertIn('crossVersion := CrossVersion.binary', project_section(self.build, "annotation"))
        self.assertIn('crossVersion := CrossVersion.full', project_section(self.build, "plugin"))
        self.assertIn('publish / skip := true', project_section(self.build, "root"))
        self.assertNotRegex(self.build, r"(?m)^\s*(?:ThisBuild / )?(?:publishTo|credentials)\s*:=" )

    def test_readme_prepares_0_1_0_without_claiming_central_availability(self) -> None:
        self.assertIn('val allowExperimentalVersion = "0.1.0"', self.readme)
        self.assertIn("No release has been published to Maven Central yet.", self.readme)
        self.assertNotRegex(
            self.readme.lower(),
            r"(?:is now|artifacts are) available (?:on|from) maven central",
        )

    def test_rehearsal_tools_are_task_local_and_fail_closed(self) -> None:
        local_release = (ROOT / "scripts/rehearse-local-release.sh").read_text(encoding="utf-8")
        consumer = (ROOT / "scripts/verify-release-consumers.sh").read_text(encoding="utf-8")
        checker = (ROOT / "scripts/check-release-repository.py").read_text(encoding="utf-8")
        signing = (ROOT / "scripts/rehearse-release-signing.py").read_text(encoding="utf-8")
        self.assertIn('VERSION=0.1.0', local_release)
        self.assertIn('SCALA_VERSIONS=(3.3.8 3.8.4 3.9.0)', local_release)
        self.assertIn('credentials := Nil', local_release)
        self.assertIn('Resolver.file', local_release)
        self.assertIn('annotation/publish', local_release)
        self.assertIn('plugin/publish', local_release)
        self.assertIn('check-release-repository.py', local_release)
        self.assertIn('rehearse-release-signing.py', local_release)
        self.assertIn('verify-release-consumers.sh', local_release)
        for lane in SCALA_VERSIONS:
            self.assertIn(lane, consumer)
        for required in (
            "direct-negative",
            "marker-without-plugin",
            "wrong-plugin",
            "symbolInfoSummary",
            "downstream",
        ):
            self.assertIn(required, consumer)
        combined = "\n".join((local_release, consumer, checker, signing)).lower()
        for forbidden in (
            "publishsigned",
            "central.sonatype.com",
            "centralportal",
            "git tag",
            "gh release",
            "credentials.add",
        ):
            self.assertNotIn(forbidden, combined)
        self.assertIn("EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD", signing)
        self.assertIn("EXPECTED_PRIMARY_COUNT = 16", signing)
        self.assertIn("EXPECTED_PRIMARY_COUNT = 16", checker)


if __name__ == "__main__":
    unittest.main(verbosity=2)
