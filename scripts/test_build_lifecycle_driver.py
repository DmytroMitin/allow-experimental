#!/usr/bin/env python3

import io
import json
import unittest

import build_lifecycle_driver


class JsonRpcFramingTest(unittest.TestCase):
    def test_sbt_frame_round_trip_includes_trailing_crlf_in_length(self):
        message = {
            "jsonrpc": "2.0",
            "id": "7",
            "method": "build/initialize",
            "params": {"displayName": "m5c"},
        }

        encoded = build_lifecycle_driver.encode_message(message)
        header, payload = encoded.split(b"\r\n\r\n", 1)
        declared = int(header.removeprefix(b"Content-Length: "))

        self.assertEqual(declared, len(payload))
        self.assertTrue(payload.endswith(b"\r\n"))
        self.assertEqual(build_lifecycle_driver.read_message(io.BytesIO(encoded)), message)


class BuildTargetSelectionTest(unittest.TestCase):
    def test_selects_compile_target_by_exact_project_display_name(self):
        response = {
            "targets": [
                {"id": {"uri": "file:/fixture#provider/Compile"}, "displayName": "provider"},
                {"id": {"uri": "file:/fixture#allowed/Compile"}, "displayName": "allowed"},
                {"id": {"uri": "file:/fixture#allowed/Test"}, "displayName": "allowed-test"},
            ]
        }

        selected = build_lifecycle_driver.select_compile_target(response, "allowed")

        self.assertEqual(selected, {"uri": "file:/fixture#allowed/Compile"})

    def test_rejects_ambiguous_or_missing_compile_target(self):
        with self.assertRaisesRegex(ValueError, "exactly one Compile target"):
            build_lifecycle_driver.select_compile_target({"targets": []}, "consumer")


class BspCompileResultTest(unittest.TestCase):
    def test_accepts_sbt_jsonrpc_compile_failure_when_diagnostic_is_stock_rejection(self):
        response = {
            "jsonrpc": "2.0",
            "id": "4",
            "error": {"code": -32603, "message": "(allowed / Compile / compileIncremental) Compilation failed"},
        }
        notifications = [{
            "method": "build/publishDiagnostics",
            "params": {"diagnostics": [{"message": "method provider is marked @experimental"}]},
        }]

        build_lifecycle_driver.validate_bsp_compile(response, notifications, False, "B1")

    def test_rejects_jsonrpc_error_for_expected_success(self):
        with self.assertRaisesRegex(AssertionError, "returned JSON-RPC error"):
            build_lifecycle_driver.validate_bsp_compile(
                {"error": {"code": -32603, "message": "Compilation failed"}}, [], True, "B0")


if __name__ == "__main__":
    unittest.main()
