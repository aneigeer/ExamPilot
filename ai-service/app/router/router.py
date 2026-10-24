"""意图路由（Router）：用 bge 句向量做 skill 分派。

## 三闸门（顺序短路，任一步命中即结束）

```
① sim  < 0.60              → REFUSE（明显域外）
② margin < 0.05            → CLARIFY（Top1 与 Top2 太接近，意图歧义）
③ 事实类 skill 检索无命中   → REFUSE（由调用方在检索后判定，见 FACT_SKILLS）
④ 其余                     → 进入 skill 执行
```

**为什么不能只用一个阈值**（设计文档 B.2 实测）：
```
域外最高 0.6317（"这个单词怎么念"）  >  域内最低 0.6136（"面试"）
```
bge 衡量的是**语义相似度**，而"能不能答"是**有无资料**的问题，两者不是同一维度。
所以 Router 只判意图，**可作答性交给检索**——"这个单词怎么念"会被正确路由到
COURSE_QA，然后在检索验证闸门被拒，最终行为仍然正确。

## 性能

原型句向量在**首次调用时**批量算一次并缓存（约 40 句，388ms 量级），
之后每次判定只算 1 条 query（约 22ms），远快于"让 7B 判断该干什么"（≥1s）。
"""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path
from typing import Dict, List, Optional

from ..clients.embedding_client import get_embedding_client
from .prototypes import PROTOTYPES
from .schemas import RouterDecision, SkillName

logger = logging.getLogger(__name__)

#: 低于此值直接拒答。标定依据见设计文档 B.4（域外/域内有重叠，取在域外一侧）。
DEFAULT_SIM_THRESHOLD = 0.60
#: Top1 与 Top2 差值小于此值视为意图歧义（实测清晰意图 margin > 0.10）。
DEFAULT_MARGIN_THRESHOLD = 0.05


def _dot(a: List[float], b: List[float]) -> float:
    """点积。bge 输出已 L2 归一化，点积即余弦相似度。"""
    return sum(x * y for x, y in zip(a, b))


#: RSI 自改进产物：由 `tools/rsi_tune.py` 写入，服务启动时自动加载。
#: 放在独立 JSON 里而不是改源码，是为了**可回滚**（删文件即恢复出厂原型句）。
LEARNED_FILE = Path(__file__).with_name("prototypes_learned.json")


def merged_prototypes() -> Dict[SkillName, List[str]]:
    """出厂原型句 + 学习到的原型句（文件缺失时只用出厂）。

    学习结果格式：`{"COURSE_QA": ["失败过的问题", ...], ...}`
    """
    merged: Dict[SkillName, List[str]] = {k: list(v) for k, v in PROTOTYPES.items()}
    if not LEARNED_FILE.exists():
        return merged
    try:
        learned = json.loads(LEARNED_FILE.read_text(encoding="utf-8"))
        for skill_name, sentences in learned.items():
            skill = SkillName(skill_name)
            bucket = merged.setdefault(skill, [])
            for sentence in sentences:
                if sentence not in bucket:
                    bucket.append(sentence)
        logger.info("[Router] 已加载自改进原型句：%d 条", sum(len(v) for v in learned.values()))
    except Exception as exc:  # noqa: BLE001 - 学习文件损坏不能拖垮路由
        logger.warning("[Router] 自改进原型句加载失败，忽略：%s", exc)
    return merged


class IntentRouter:
    """把问题映射到 skill。

    线程安全：原型句向量只算一次，用锁保护双重检查。
    容错：embedding 服务不可用时由调用方捕获异常并**跳过 Router**
    （退化为"直接走通用检索问答"，与接入 Router 之前的行为一致，零退化）。
    """

    def __init__(
        self,
        sim_threshold: float = DEFAULT_SIM_THRESHOLD,
        margin_threshold: float = DEFAULT_MARGIN_THRESHOLD,
    ) -> None:
        self.sim_threshold = sim_threshold
        self.margin_threshold = margin_threshold
        self._prototype_vectors: Optional[List[tuple[SkillName, List[float]]]] = None
        self._lock = threading.Lock()

    # ---------------------------------------------------------------- 原型句
    def _ensure_prototypes(self) -> List[tuple[SkillName, List[float]]]:
        """首次调用时把全部原型句向量化并缓存。"""
        if self._prototype_vectors is not None:
            return self._prototype_vectors
        with self._lock:
            if self._prototype_vectors is not None:
                return self._prototype_vectors

            pairs: List[tuple[SkillName, str]] = [
                (skill, sentence)
                for skill, sentences in merged_prototypes().items()
                for sentence in sentences
            ]
            client = get_embedding_client()
            vectors = client.encode([s for _, s in pairs], is_query=False)
            self._prototype_vectors = [
                (skill, vec) for (skill, _), vec in zip(pairs, vectors)
            ]
            logger.info(
                "[Router] 原型句向量已缓存：%d 条 / %d 个 skill",
                len(self._prototype_vectors), len(PROTOTYPES),
            )
            return self._prototype_vectors

    # ---------------------------------------------------------------- 判定
    def decide(self, question: str) -> RouterDecision:
        """判定意图。返回的 skill 可能是 REFUSE / CLARIFY。"""
        text = (question or "").strip()
        if not text:
            return RouterDecision(SkillName.CLARIFY, 0.0, 0.0, [], "EMPTY")

        query_vec = get_embedding_client().encode_query(text)
        # 每个 skill 取"最像的那条原型句"的分数
        best_by_skill: dict[SkillName, float] = {}
        for skill, vec in self._ensure_prototypes():
            score = _dot(query_vec, vec)
            if score > best_by_skill.get(skill, -1.0):
                best_by_skill[skill] = score

        ranked = sorted(best_by_skill.items(), key=lambda kv: kv[1], reverse=True)
        top_skill, top_sim = ranked[0]
        second_sim = ranked[1][1] if len(ranked) > 1 else 0.0
        margin = top_sim - second_sim
        candidates = [
            {"skill": s.value, "sim": round(v, 4)} for s, v in ranked[:3]
        ]

        if top_sim < self.sim_threshold:
            logger.info(
                "[Router] REFUSE sim=%.4f < %.2f q=%s", top_sim, self.sim_threshold, text[:24]
            )
            return RouterDecision(SkillName.REFUSE, top_sim, margin, candidates, "SIM_LOW")

        if margin < self.margin_threshold:
            logger.info(
                "[Router] CLARIFY sim=%.4f margin=%.4f q=%s", top_sim, margin, text[:24]
            )
            return RouterDecision(SkillName.CLARIFY, top_sim, margin, candidates, "MARGIN_LOW")

        logger.info(
            "[Router] ROUTED skill=%s sim=%.4f margin=%.4f q=%s",
            top_skill.value, top_sim, margin, text[:24],
        )
        return RouterDecision(top_skill, top_sim, margin, candidates, "ROUTED")


_router: Optional[IntentRouter] = None
_router_lock = threading.Lock()


def get_router() -> IntentRouter:
    """进程级单例（原型句向量随之只算一次）。"""
    global _router
    if _router is None:
        with _router_lock:
            if _router is None:
                _router = IntentRouter()
    return _router
