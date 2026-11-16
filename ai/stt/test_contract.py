"""요청4/결과2 계약 fixture 검사 (모델 없이 실행된다).

examples/의 요청·성공·실패 예시가 contract.py 모델을 통과하는지, schema/가 모델과 같은지 확인한다.
실행: cd ai/stt && uv run python -m unittest test_contract -v
"""

import json
import unittest
from pathlib import Path

from pydantic import ValidationError

import contract

HERE = Path(__file__).resolve().parent
EXAMPLES = HERE / "examples"


def load(name):
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


def model_for(name):
    if name.startswith("request"):
        return contract.TranscribeRequest
    if name.startswith("success"):
        return contract.Success
    if name.startswith("failure"):
        return contract.Failure
    raise AssertionError(f"알 수 없는 예시 이름: {name}")


class ExamplesTest(unittest.TestCase):
    def test_every_example_matches_contract(self):
        names = sorted(p.name for p in EXAMPLES.glob("*.json"))
        self.assertGreaterEqual(len(names), 10)
        for name in names:
            with self.subTest(name):
                model_for(name).model_validate(load(name))

    def test_success_matches_request(self):
        request, success = load("request.json"), load("success.json")
        self.assertEqual({k: success[k] for k in contract.IDENTITY_ADAPTERS},
                         {k: request[k] for k in contract.IDENTITY_ADAPTERS})
        self.assertEqual(success["result"]["audioId"], request["payload"]["audioId"])
        self.assertEqual(success["resultSchemaVersion"], contract.RESULT_SCHEMA_VERSION)

    def test_after_acceptance_failures_keep_identity(self):
        request = load("request.json")
        for name in ("failure-timeout.json", "failure-audio-invalid.json", "failure-fetch-unavailable.json",
                     "failure-no-speech.json", "failure-busy.json"):
            with self.subTest(name):
                failure = load(name)
                self.assertEqual({k: failure[k] for k in contract.IDENTITY_ADAPTERS},
                                 {k: request[k] for k in contract.IDENTITY_ADAPTERS})

    def test_pre_acceptance_failure_has_null_keys(self):
        failure = load("failure-pre-acceptance.json")
        self.assertEqual({k: failure[k] for k in contract.IDENTITY_ADAPTERS}, contract.UNKNOWN_IDENTITY)

    def test_identity_keys_are_required(self):
        failure = load("failure-pre-acceptance.json")
        del failure["jobId"]
        with self.assertRaises(ValidationError):
            contract.Failure.model_validate(failure)

    def test_success_requires_pipeline(self):
        success = load("success.json")
        del success["executionMeta"]["pipeline"]
        with self.assertRaises(ValidationError):
            contract.Success.model_validate(success)

    def test_legacy_result1_rejected(self):
        success = load("success.json")
        success["resultSchemaVersion"] = "1"
        success["result"]["transcript"] = success["result"].pop("text")
        with self.assertRaises(ValidationError):
            contract.Success.model_validate(success)


class KnownIdentityTest(unittest.TestCase):
    def test_keeps_only_valid_fields(self):
        body = {**load("request.json"), "jobId": "job-1", "attempt": 0}
        identity = contract.known_identity(body)
        self.assertEqual(identity, {"jobId": None, "attempt": None, "requestSchemaVersion": "4",
                                    "inputHash": "a" * 64, "contextRevision": 12})

    def test_not_an_object(self):
        for body in ("{not json", None, [1, 2]):
            with self.subTest(body=body):
                self.assertEqual(contract.known_identity(body), contract.UNKNOWN_IDENTITY)


class SchemaFilesTest(unittest.TestCase):
    def test_schema_files_are_current(self):
        for name, schema in contract.json_schemas().items():
            with self.subTest(name):
                saved = json.loads((HERE / "schema" / name).read_text(encoding="utf-8"))
                self.assertEqual(saved, schema, "uv run python contract.py schema schema 로 다시 생성하세요.")


if __name__ == "__main__":
    unittest.main()
