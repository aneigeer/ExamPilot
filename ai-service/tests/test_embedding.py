"""``POST /v1/embed`` 测试。

覆盖：
* 维度必须为 512（Java 侧按此建 Qdrant collection）
* 向量必须 L2 归一化（余弦相似度 == 点积的前提）
* 批量条数与顺序一一对应
* 参数错误 400
* **模型缺失时 503 降级**（不是 500）
"""

from __future__ import annotations

import math
from typing import Any

#: 与 bge-small-zh-v1.5 一致；Java 侧 Qdrant collection 也按此维度建
EXPECTED_DIM = 512


def test_embed_returns_512_dim(client: Any) -> None:
    """单条文本必须返回 512 维向量。"""
    response = client.post("/v1/embed", json={"texts": ["动态分配内存的函数"]})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["dim"] == EXPECTED_DIM
    assert len(data["vectors"]) == 1
    assert len(data["vectors"][0]) == EXPECTED_DIM


def test_embed_batch_size_matches(client: Any) -> None:
    """批量条数与返回向量数一一对应，顺序保持。"""
    texts = ["指针", "栈与队列", "时间复杂度", "文件操作", "进程间通信"]
    response = client.post("/v1/embed", json={"texts": texts})
    assert response.status_code == 200
    data = response.json()
    assert len(data["vectors"]) == len(texts)
    assert all(len(vector) == EXPECTED_DIM for vector in data["vectors"])


def test_embed_vectors_are_l2_normalized(client: Any) -> None:
    """**关键不变量**：L2 范数必须为 1.0（否则 Qdrant COSINE 结果不可用）。"""
    response = client.post("/v1/embed", json={"texts": ["操作系统内存管理", "计算机网络"]})
    assert response.status_code == 200
    for vector in response.json()["vectors"]:
        norm = math.sqrt(sum(value * value for value in vector))
        assert abs(norm - 1.0) < 1e-5, f"L2 范数应为 1.0，实际 {norm}"


def test_embed_response_has_took_ms(client: Any) -> None:
    """响应带耗时字段（Java 侧用于超时统计）。"""
    data = client.post("/v1/embed", json={"texts": ["测试"]}).json()
    assert "took_ms" in data
    assert data["took_ms"] >= 0


def test_embed_empty_texts_returns_400(client: Any) -> None:
    """空列表返回 400 + 统一错误体。"""
    response = client.post("/v1/embed", json={"texts": []})
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == 5200
    assert body["degraded"] is False


def test_embed_blank_text_returns_400(client: Any) -> None:
    """空白字符串返回 400。"""
    response = client.post("/v1/embed", json={"texts": ["正常文本", "   "]})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_embed_too_many_texts_returns_400(client: Any) -> None:
    """超过 64 条上限返回 400（防止单请求打爆 CPU）。"""
    response = client.post("/v1/embed", json={"texts": ["短"] * 65})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_embed_missing_field_returns_400(client: Any) -> None:
    """缺字段返回 400（不是 500）。"""
    response = client.post("/v1/embed", json={})
    assert response.status_code == 400
    assert response.json()["code"] == 5200


def test_embed_degrades_to_503_when_model_missing(raw_client: Any) -> None:
    """**核心降级用例**：模型文件缺失时必须 503，绝不能 500。"""
    response = raw_client.post("/v1/embed", json={"texts": ["测试"]})
    assert response.status_code == 503, (
        f"模型缺失应返回 503，实际 {response.status_code}：{response.text}"
    )
    body = response.json()
    assert body["degraded"] is True
    assert body["code"] in (5201, 5204), f"错误码应为 5201/5204，实际 {body['code']}"
    assert "Traceback" not in body["message"]


def test_embed_degradation_message_is_actionable(raw_client: Any) -> None:
    """降级提示要让 Java 侧/运维知道该做什么（装依赖 or 放模型）。"""
    body = raw_client.post("/v1/embed", json={"texts": ["测试"]}).json()
    assert any(
        keyword in body["message"]
        for keyword in ("onnxruntime", "install.bat", "模型")
    ), f"降级提示不可操作：{body['message']}"
