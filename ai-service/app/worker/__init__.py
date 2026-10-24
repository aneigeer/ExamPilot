"""异步任务消费者（Redis Stream）。"""

from .job_worker import StreamWorker, start_workers

# 导入即注册 EVAL_RETRIEVAL / EVAL_AGENT / WIKI_DISTILL 处理器
from . import eval_handlers as _eval_handlers  # noqa: F401,E402
from . import wiki_handlers as _wiki_handlers  # noqa: F401,E402

__all__ = ["StreamWorker", "start_workers"]
