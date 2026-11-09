#!/usr/bin/env bash
# 第 9 周 P0 备份恢复演练 —— 逐表行数统计脚本（只读）
# 用法: ./backup_drill_20261109_counts.sh <database> <output_tsv>
# 说明: 仅执行 SELECT COUNT(*)，不对 exam_prep 做任何写操作。
set -uo pipefail

DB="${1:-exam_prep}"
OUT="${2:-./counts.tsv}"
MYSQL=(mysql -uroot -p123456 --default-character-set=utf8mb4 -N -B)

# 注意: Windows 版 mysql -B 输出为 CRLF，必须去掉 \r，否则表名会带上回车导致 ERROR 1103
TABLES=$("${MYSQL[@]}" -e "SELECT table_name FROM information_schema.tables WHERE table_schema='${DB}' AND table_type='BASE TABLE' ORDER BY table_name;" 2>/dev/null | tr -d '\r')

SQL=""
for t in $TABLES; do
  if [ -z "$SQL" ]; then
    SQL="SELECT '${t}' AS table_name, COUNT(*) AS row_count FROM \`${DB}\`.\`${t}\`"
  else
    SQL="${SQL} UNION ALL SELECT '${t}', COUNT(*) FROM \`${DB}\`.\`${t}\`"
  fi
done

"${MYSQL[@]}" -e "${SQL}" 2>/dev/null > "$OUT"
echo "tables=$(echo "$TABLES" | wc -l) out=$OUT"
