"""
ExamPilot —— 大学生多考试备考知识库与题库系统 · AI 微服务（Python 侧）。

职责边界（见 docs/AI微服务扩展设计方案.md 章节 A）：
    本服务只负责 ① 文本 embedding ② 向量库检索与重排 ③ OCR 试卷识别 ④ 大模型代理。
    不碰业务库、不做鉴权决策、不直接写 question 表。

降级底线：
    任何依赖缺失或不可用时，对应接口返回 503 + {"code":5xxx,"message":"...","degraded":true}，
    由 Java 侧降级到 ngram 全文检索 + 本地抽取式，系统功能零损失。
"""

__all__ = ["__version__"]

__version__ = "1.0.0"
