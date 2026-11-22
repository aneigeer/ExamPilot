"""Qdrant payload 索引：服务端创建、本地模式跳过。"""

from __future__ import annotations

from app.clients.qdrant_client import QdrantClientWrapper
from app.config import QdrantSettings


class FakeClient:
    def __init__(self):
        self.created = []
        self.collections = set()

    def collection_exists(self, collection_name):
        return collection_name in self.collections

    def create_collection(self, collection_name, vectors_config):
        self.collections.add(collection_name)

    def create_payload_index(self, collection_name, field_name, field_schema):
        self.created.append((collection_name, field_name, field_schema))


def test_server_mode_creates_four_indexes(monkeypatch):
    settings = QdrantSettings(url="http://127.0.0.1:6333", collection="exam_chunks")
    wrapper = QdrantClientWrapper(settings)
    fake = FakeClient()
    monkeypatch.setattr(wrapper, "_ensure_client", lambda: fake)
    wrapper.ensure_collection()
    fields = [item[1] for item in fake.created]
    assert fields == ["status", "source_type", "subject_id", "knowledge_id"]


def test_local_mode_skips_indexes(monkeypatch, tmp_path):
    settings = QdrantSettings(url=f"local:{tmp_path}", collection="exam_chunks")
    wrapper = QdrantClientWrapper(settings)
    fake = FakeClient()
    monkeypatch.setattr(wrapper, "_ensure_client", lambda: fake)
    wrapper.ensure_collection()
    assert fake.created == []
