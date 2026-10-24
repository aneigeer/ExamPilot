-- ============================================================================
--  数据修复：把「依赖图片/模板题干」的题目块移出检索语料
--
--  背景（2026-10-23 实测）
--    行测类题目中存在一批「题干是纯模板、真正题目在图片里」的题型：
--      图形推理  「从所给的四个选项中，选最合适的一个填入问号处…」
--      文章标题  「最适合做这篇文章标题的是：」
--      类比推理  「X 对于 ___ 相当于 ___ 对于 Y」
--      语句排序  「重新排列…」
--      缺图      「如图 / 下图 / 图中」
--    这些块的正文里没有图，语义上与同题型的所有题几乎相同：
--      · 检索时它们会彼此挤压 top-K（同标题最多 18 条）
--      · 命中后大模型只能拿到"解析：元素组成不同…"这类脱离图片的碎片
--      · 实测「量子纠缠」类拒答用例被这类块污染，拒答边界失效
--
--  处置
--    只把 ai_chunk.status 置为 INVALID（保留数据、可逆），
--    **不动 question 表**——学生端仍可正常练习这些题，只是 AI 问答不再引用它们。
--
--  幂等：重复执行影响行数为 0
-- ============================================================================

UPDATE ai_chunk
SET status = 'INVALID',
    updated_at = NOW()
WHERE status = 'ACTIVE'
  AND source_type = 'QUESTION'
  AND (
        title LIKE '%填入问号处%'
     OR title LIKE '%呈现一定的规律性%'
     OR title LIKE '%做这篇文章标题的是%'
     OR title LIKE '%相当于%对于%'
     OR title LIKE '%重新排列%'
     OR title LIKE '%如图%'
     OR title LIKE '%下图%'
     OR title LIKE '%图中%'
  );

-- 校验：剩余 ACTIVE 块数、被移出的块数
SELECT
    (SELECT COUNT(*) FROM ai_chunk WHERE status = 'ACTIVE')                                  AS 有效块,
    (SELECT COUNT(*) FROM ai_chunk WHERE status = 'INVALID' AND source_type = 'QUESTION')     AS 已移出题目块;
