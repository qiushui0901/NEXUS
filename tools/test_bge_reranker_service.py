import importlib.util
import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("bge-reranker-service.py")
SPEC = importlib.util.spec_from_file_location("bge_reranker_service", MODULE_PATH)
service = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = service
SPEC.loader.exec_module(service)


class FakeReranker:
    model_id = "test-reranker"
    device = "cpu"

    def score(self, query, texts, truncate):
        del query, truncate
        return [0.9 if "match" in text else 0.1 for text in texts]


class RerankerServerTest(unittest.TestCase):
    def setUp(self):
        handler = service.build_handler(
            FakeReranker(), api_key="test-key", max_texts=4, max_request_bytes=4096
        )
        self.server = service.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def request(self, path, body=None, api_key="test-key"):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if api_key is not None:
            headers["Authorization"] = f"Bearer {api_key}"
        req = urllib.request.Request(self.base_url + path, data=data, headers=headers)
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, json.load(response)

    def test_health_exposes_readiness_without_loading_request_text(self):
        status, body = self.request("/health", api_key=None)
        self.assertEqual(200, status)
        self.assertEqual("UP", body["status"])
        self.assertEqual("test-reranker", body["model"])

    def test_rerank_returns_current_nexus_array_contract(self):
        status, body = self.request(
            "/rerank",
            {"query": "match", "texts": ["not relevant", "match passage"], "truncate": True},
        )
        self.assertEqual(200, status)
        self.assertEqual([{"index": 0, "score": 0.1}, {"index": 1, "score": 0.9}], body)

    def test_rerank_rejects_missing_bearer_token(self):
        with self.assertRaises(urllib.error.HTTPError) as context:
            self.request(
                "/rerank",
                {"query": "match", "texts": ["match passage"], "truncate": True},
                api_key=None,
            )
        self.assertEqual(401, context.exception.code)

    def test_rerank_rejects_invalid_or_oversized_candidate_lists(self):
        for texts in ([], ["match"] * 5):
            with self.subTest(texts=len(texts)):
                with self.assertRaises(urllib.error.HTTPError) as context:
                    self.request("/rerank", {"query": "match", "texts": texts, "truncate": True})
                self.assertEqual(400, context.exception.code)


if __name__ == "__main__":
    unittest.main()
