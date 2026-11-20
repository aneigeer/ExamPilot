#!/usr/bin/env bash
# 第 9 周 P0 备份恢复演练 —— 逐表行数统计脚本（只读）
# 用法: ./backup_drill_20261109_counts.sh <database> <output_tsv>
# 说明: 仅执行 SELECT COUNT(*)，不对 exam_prep 做任何写操作。
set -uo pipefail

DB="${1:-exam_prep}"
OUT="${2:-./counts.tsv}"

# 口令来源（第10周 P0-1 起仓库内不再出现明文 root 口令）：
#   1) 环境变量 DB_PASSWORD
#   2) 仓库根 config/secrets.local.yml 的 mysql.root.password（该文件已被 .gitignore 排除）
# 口令文件是扁平点号键，一行一取，不需要 YAML 解析器。
# 走 MYSQL_PWD 传递，避免口令出现在进程命令行（ps / 任务管理器可见）里。
if [ -z "${DB_PASSWORD:-}" ]; then
  SECRETS_FILE="$(cd "$(dirname "$0")/../../../" && pwd)/config/secrets.local.yml"
  if [ -f "$SECRETS_FILE" ]; then
    DB_PASSWORD=$(sed -n 's/^mysql\.root\.password:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "$SECRETS_FILE" | head -1)
  fi
fi
if [ -z "${DB_PASSWORD:-}" ]; then
  echo "未取到数据库口令：请设置环境变量 DB_PASSWORD，或配置 config/secrets.local.yml 的 mysql.root.password" >&2
  exit 2
fi
export MYSQL_PWD="$DB_PASSWORD"

MYSQL=(mysql -uroot --default-character-set=utf8mb4 -N -B)

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
