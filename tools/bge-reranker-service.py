#!/usr/bin/env python3
"""Serve BAAI BGE reranking through the JSON contract used by NEXUS.

The service intentionally runs separately from Ollama: Ollama's embedding endpoint returns
vectors, while this service executes the sequence-classification head and returns one relevance
score per query/text pair.
"""
from __future__ import annotations

import argparse
import hmac
import json
import logging
import os
import threading
from dataclasses import dataclass, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Protocol
from urllib.parse import urlsplit

LOGGER = logging.getLogger("nexus.bge_reranker")
DEFAULT_MODEL_ID = "BAAI/bge-reranker-v2-m3"


def env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class RerankerConfig:
    host: str = "127.0.0.1"
    port: int = 8081
    model_id: str = DEFAULT_MODEL_ID
    device: str = "auto"
    max_length: int = 8192
    batch_size: int = 4
    normalize: bool = True
    api_key: str = ""
    max_texts: int = 128
    max_request_bytes: int = 8 * 1024 * 1024

    @classmethod
    def from_env(cls) -> "RerankerConfig":
        return cls(
            host=os.getenv("BGE_RERANK_HOST", "127.0.0.1"),
            port=int(os.getenv("BGE_RERANK_PORT", "8081")),
            model_id=os.getenv("BGE_RERANK_MODEL_ID", DEFAULT_MODEL_ID),
            device=os.getenv("BGE_RERANK_DEVICE", "auto"),
            max_length=int(os.getenv("BGE_RERANK_MAX_LENGTH", "8192")),
            batch_size=int(os.getenv("BGE_RERANK_BATCH_SIZE", "4")),
            normalize=env_bool("BGE_RERANK_NORMALIZE", True),
            api_key=os.getenv("BGE_RERANK_API_KEY", ""),
            max_texts=int(os.getenv("BGE_RERANK_MAX_TEXTS", "128")),
            max_request_bytes=int(os.getenv("BGE_RERANK_MAX_REQUEST_BYTES", str(8 * 1024 * 1024))),
        )


class Reranker(Protocol):
    model_id: str
    device: str

    def score(self, query: str, texts: list[str], truncate: bool) -> list[float]: ...


class TransformersReranker:
    """Thread-safe Transformers sequence-classification reranker."""

    def __init__(self, config: RerankerConfig) -> None:
        self.config = config
        self.model_id = config.model_id
        self.device = config.device
        self._torch: Any = None
        self._tokenizer: Any = None
        self._model: Any = None
        self._inference_lock = threading.Lock()

    def load(self) -> None:
        try:
            import torch
            from transformers import AutoModelForSequenceClassification, AutoTokenizer
        except ImportError as error:
            raise RuntimeError(
                "Missing reranker dependencies. Run tools/start-bge-reranker.sh to create the Python 3.11 environment."
            ) from error

        self.device = self._resolve_device(torch)
        LOGGER.info("Loading reranker model=%s device=%s", self.model_id, self.device)
        self._tokenizer = AutoTokenizer.from_pretrained(self.model_id, trust_remote_code=True)
        self._model = AutoModelForSequenceClassification.from_pretrained(
            self.model_id,
            trust_remote_code=True,
        )
        self._model.to(self.device)
        self._model.eval()
        self._torch = torch
        LOGGER.info("Reranker ready model=%s device=%s", self.model_id, self.device)

    def _resolve_device(self, torch: Any) -> str:
        requested = self.config.device.strip().lower()
        if requested != "auto":
            return requested
        if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            return "mps"
        if torch.cuda.is_available():
            return "cuda"
        return "cpu"

    def score(self, query: str, texts: list[str], truncate: bool) -> list[float]:
        if self._model is None or self._tokenizer is None or self._torch is None:
            raise RuntimeError("Reranker model has not been loaded")

        scores: list[float] = []
        with self._inference_lock, self._torch.inference_mode():
            for start in range(0, len(texts), self.config.batch_size):
                batch = texts[start : start + self.config.batch_size]
                tokenization: dict[str, Any] = {
                    "padding": True,
                    "truncation": truncate,
                    "return_tensors": "pt",
                }
                if truncate:
                    tokenization["max_length"] = self.config.max_length
                inputs = self._tokenizer([query] * len(batch), batch, **tokenization)
                inputs = {name: tensor.to(self.device) for name, tensor in inputs.items()}
                logits = self._model(**inputs, return_dict=True).logits.reshape(-1).float()
                if self.config.normalize:
                    logits = self._torch.sigmoid(logits)
                scores.extend(float(value) for value in logits.cpu().tolist())
        if len(scores) != len(texts):
            raise RuntimeError("Reranker returned an unexpected score count")
        return scores


def validate_request(payload: Any, max_texts: int) -> tuple[str, list[str], bool]:
    if not isinstance(payload, dict):
        raise ValueError("request body must be a JSON object")
    query = payload.get("query")
    texts = payload.get("texts")
    truncate = payload.get("truncate", True)
    if not isinstance(query, str) or not query.strip():
        raise ValueError("query must be a non-empty string")
    if not isinstance(texts, list) or not texts:
        raise ValueError("texts must be a non-empty array")
    if len(texts) > max_texts:
        raise ValueError(f"texts exceeds the maximum of {max_texts}")
    if not all(isinstance(text, str) and text.strip() for text in texts):
        raise ValueError("every texts entry must be a non-empty string")
    if not isinstance(truncate, bool):
        raise ValueError("truncate must be a boolean")
    return query, texts, truncate


def build_handler(
    reranker: Reranker,
    *,
    api_key: str,
    max_texts: int,
    max_request_bytes: int,
) -> type[BaseHTTPRequestHandler]:
    class RerankerHandler(BaseHTTPRequestHandler):
        server_version = "NexusBgeReranker/1.0"

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if urlsplit(self.path).path != "/health":
                self._write_json(404, {"error": "not found"})
                return
            self._write_json(
                200,
                {
                    "status": "UP",
                    "model": reranker.model_id,
                    "device": reranker.device,
                },
            )

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if urlsplit(self.path).path != "/rerank":
                self._write_json(404, {"error": "not found"})
                return
            if api_key and not self._authorized(api_key):
                self._write_json(401, {"error": "unauthorized"})
                return
            try:
                payload = self._read_json(max_request_bytes)
                query, texts, truncate = validate_request(payload, max_texts)
                scores = reranker.score(query, texts, truncate)
                response = [{"index": index, "score": score} for index, score in enumerate(scores)]
                self._write_json(200, response)
            except RequestTooLargeError as error:
                self._write_json(413, {"error": str(error)})
            except (ValueError, json.JSONDecodeError) as error:
                self._write_json(400, {"error": str(error)})
            except Exception as error:  # keep model/runtime details out of HTTP responses
                LOGGER.exception("Rerank request failed exceptionType=%s", type(error).__name__)
                self._write_json(500, {"error": "rerank failed"})

        def _authorized(self, expected: str) -> bool:
            authorization = self.headers.get("Authorization", "")
            prefix = "Bearer "
            return authorization.startswith(prefix) and hmac.compare_digest(
                authorization[len(prefix) :], expected
            )

        def _read_json(self, limit: int) -> Any:
            raw_length = self.headers.get("Content-Length")
            if raw_length is None:
                raise ValueError("Content-Length is required")
            try:
                length = int(raw_length)
            except ValueError as error:
                raise ValueError("invalid Content-Length") from error
            if length < 0 or length > limit:
                raise RequestTooLargeError(f"request body exceeds {limit} bytes")
            return json.loads(self.rfile.read(length))

        def _write_json(self, status: int, payload: Any) -> None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format_string: str, *args: Any) -> None:
            LOGGER.info("client=%s %s", self.client_address[0], format_string % args)

    return RerankerHandler


class RequestTooLargeError(ValueError):
    pass


def parse_args(config: RerankerConfig) -> RerankerConfig:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=config.host)
    parser.add_argument("--port", type=int, default=config.port)
    parser.add_argument("--model-id", default=config.model_id)
    parser.add_argument("--device", default=config.device, choices=("auto", "cpu", "mps", "cuda"))
    parser.add_argument("--max-length", type=int, default=config.max_length)
    parser.add_argument("--batch-size", type=int, default=config.batch_size)
    parser.add_argument("--max-texts", type=int, default=config.max_texts)
    args = parser.parse_args()
    if args.port <= 0 or args.port > 65535:
        parser.error("port must be between 1 and 65535")
    if args.max_length <= 0 or args.batch_size <= 0 or args.max_texts <= 0:
        parser.error("max-length, batch-size and max-texts must be positive")
    return replace(
        config,
        host=args.host,
        port=args.port,
        model_id=args.model_id,
        device=args.device,
        max_length=args.max_length,
        batch_size=args.batch_size,
        max_texts=args.max_texts,
    )


def main() -> None:
    logging.basicConfig(
        level=os.getenv("BGE_RERANK_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    config = parse_args(RerankerConfig.from_env())
    reranker = TransformersReranker(config)
    reranker.load()
    handler = build_handler(
        reranker,
        api_key=config.api_key,
        max_texts=config.max_texts,
        max_request_bytes=config.max_request_bytes,
    )
    server = ThreadingHTTPServer((config.host, config.port), handler)
    LOGGER.info("Listening host=%s port=%s path=/rerank", config.host, config.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        LOGGER.info("Shutdown requested")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
