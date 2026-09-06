#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts/check-release-repository.py"
SIGNER = ROOT / "scripts/rehearse-release-signing.py"
SPEC = importlib.util.spec_from_file_location(
    "allow_experimental_release_fixture", ROOT / "scripts/test-check-release-repository.py"
)
assert SPEC is not None and SPEC.loader is not None
FIXTURE_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE_MODULE)


class EphemeralSigningRehearsalTest(unittest.TestCase):
    def prepare(self, root: Path) -> tuple[Path, Path, Path]:
        repository = FIXTURE_MODULE.ReleaseRepositoryCheckerTest().fixture(root)
        manifest = root / "manifest.json"
        result = subprocess.run(
            [
                "python3",
                str(CHECKER),
                str(root / "project"),
                str(repository),
                "--source-identity",
                "a38dcd207c26ef8eb3cbf8d4e96b4bd908653173",
                "--json",
                str(manifest),
                "--markdown",
                str(root / "manifest.md"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        return repository, manifest, root / "signing-output"

    def run_signer(self, repository: Path, manifest: Path, output: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(SIGNER), str(repository), str(manifest), str(output)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def test_ephemeral_key_signs_and_verifies_all_16_primaries_without_retaining_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository, manifest, output = self.prepare(root)
            result = self.run_signer(repository, manifest, output)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn("EPHEMERAL_RELEASE_SIGNING_REHEARSAL_PASS", result.stdout)
            proof = json.loads((output / "EPHEMERAL_SIGNING_PROOF.json").read_text(encoding="utf-8"))
            unsigned = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(proof["schema"], "allow-experimental-ephemeral-signing-proof-v1")
            self.assertEqual(proof["primary_manifest_sha256"], unsigned["primary_manifest_sha256"])
            self.assertEqual(proof["signer"]["kind"], "EPHEMERAL_TEST_ONLY")
            self.assertFalse(proof["signer"]["release_authority"])
            self.assertEqual(proof["signed_primary_count"], 16)
            self.assertTrue(proof["all_signatures_verified"])
            self.assertTrue(proof["primary_bytes_unchanged"])
            self.assertFalse(proof["secret_key_material_retained"])
            self.assertTrue(proof["bundle"]["deterministic_serialization_verified"])
            self.assertEqual(proof["bundle"]["classification"], "EPHEMERAL_TEST_ONLY_NOT_FOR_UPLOAD")
            self.assertEqual(len(list((output / "signed-repository").rglob("*.asc"))), 16)
            self.assertTrue((output / "allow-experimental-central-bundle-EPHEMERAL-TEST-ONLY.zip").is_file())
            self.assertFalse(
                any(path.name in {"private-keys-v1.d", "secring.gpg"} for path in output.rglob("*"))
            )

    def test_primary_drift_fails_before_any_signing_output_survives(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository, manifest, output = self.prepare(root)
            primary = next(repository.rglob("*.pom"))
            primary.write_bytes(primary.read_bytes() + b"drift")
            result = self.run_signer(repository, manifest, output)
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn("PRIMARY_MANIFEST_MISMATCH", result.stdout)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
