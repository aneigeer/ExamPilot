#!/usr/bin/env bash
# ============================================================================
# 第 10 周 P0-1 / P0-2 可重跑验证脚本（只读）
#
# 验证："MySQL 只有本机 root 且口令为强口令" + "远程 root 已删" +
#       "监听面已收口" + "依赖数据库的脚本仍能跑" + "超管哈希格式正确"
#
# 用法：
#   bash scripts/qa/week10_p0_verify.sh
#
# 前置：必须存在 config/secrets.local.yml（内含 mysql.root.password / admin.password，
#       该文件已被 .gitignore 排除）。本脚本**从不打印口令**，只打印 PASS/FAIL。
#
# 说明：重启 MySQL 服务之前，"运行时 bind_address" 会是 0.0.0.0（老进程的内存值），
#       这属于"配置已改、待生效"，脚本会如实打印当前值而不是硬判失败。
# ============================================================================
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$REPO_ROOT" || exit 1

MYSQL_BIN="${MYSQL_BIN:-D:/bigplan/tools/mysql-8.0.46/bin}"
MY="$MYSQL_BIN/mysql.exe"
DUMP="$MYSQL_BIN/mysqldump.exe"
MYINI="${MYINI:-D:/bigplan/tools/mysql-8.0.46/my.ini}"
SECRETS="config/secrets.local.yml"
LAN_IP="${LAN_IP:-10.200.86.72}"

if [ ! -f "$SECRETS" ]; then
  echo "缺少 $SECRETS（本地凭据文件，不入库）。先按 config/secrets.example.yml 建一份。" >&2
  exit 2
fi

PWD_MYSQL=$(sed -n 's/^mysql\.root\.password:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "$SECRETS" | head -1)
PWD_ADMIN=$(sed -n 's/^admin\.password:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "$SECRETS" | head -1)

PASS_N=0
FAIL_N=0
pass() { echo "  [PASS] $1"; PASS_N=$((PASS_N + 1)); }
fail() { echo "  [FAIL] $1"; FAIL_N=$((FAIL_N + 1)); }

echo "=== A. 凭据文件 ==="
[ ${#PWD_MYSQL} -ge 20 ] && pass "mysql.root.password 长度 ${#PWD_MYSQL} (>=20)" || fail "mysql.root.password 长度不足"
[ ${#PWD_ADMIN} -ge 20 ] && pass "admin.password 长度 ${#PWD_ADMIN} (>=20)" || fail "admin.password 长度不足"
[ "$PWD_MYSQL" != "$PWD_ADMIN" ] && pass "root 与 admin 口令不同" || fail "root 与 admin 口令相同"
# 下面的 123456 / admin123 是**已知的旧弱口令字面量**，只用于"断言它已失效"，
# 不是任何当前有效凭据；出现在这里是为了让 P0 的关闭可被机器复核。
case "$PWD_MYSQL$PWD_ADMIN" in
  *123456*|*admin123*) fail "口令含弱口令片段" ;;
  *) pass "口令不含 123456/admin123 片段" ;;
esac

echo "=== B. 配置同步（同一口令只应有一处真相）==="
YML=$(sed -n 's/^[[:space:]]*password:[[:space:]]*"\([^"]*\)".*$/\1/p' backend/src/main/resources/application-local.yml | head -1)
[ "$YML" = "$PWD_MYSQL" ] && pass "application-local.yml 与 secrets 一致" || fail "application-local.yml 与 secrets 不一致"
ENVP=$(sed -n 's/^DB_PASSWORD=//p' ai-service/.env | head -1)
[ "$ENVP" = "$PWD_MYSQL" ] && pass "ai-service/.env 与 secrets 一致" || fail "ai-service/.env 与 secrets 不一致"
if [ -f config/mysql-client.local.cnf ]; then
  CNFP=$(sed -n 's/^password="\(.*\)"$/\1/p' config/mysql-client.local.cnf | head -1)
  [ "$CNFP" = "$PWD_MYSQL" ] && pass "mysql-client.local.cnf 与 secrets 一致" || fail "mysql-client.local.cnf 与 secrets 不一致"
else
  fail "缺少 config/mysql-client.local.cnf（backup.bat 免密登录用）"
fi
# 构建产物里的资源副本：手拼 classpath 时排在前面的那一份，若是旧口令会报 Access denied
TARGET_YML="backend/target/classes/application-local.yml"
if [ -f "$TARGET_YML" ]; then
  if cmp -s backend/src/main/resources/application-local.yml "$TARGET_YML"; then
    pass "target/classes 资源副本与 src 一致（手拼 classpath 不会用到旧口令）"
  else
    fail "target/classes/application-local.yml 与 src 不一致，手跑请把 src/main/resources 排在 classpath 前面，或先 mvn 重新构建"
  fi
fi
# compose 文件禁止"非空默认值"：`${VAR:-值}` 这种兜底会让弱口令悄悄回潮（如 `:-minioadmin`）。
# `:?`（缺失即快速失败）与 `:-}`（空默认）放行；QDRANT_API_KEY 那行是注释 + 空默认，白名单放行。
BAD_DEFAULT=$(grep -nE '\$\{[A-Za-z_.-]+:-[^}]+\}' docker-compose*.yml 2>/dev/null | grep -v 'QDRANT_API_KEY:-}' || true)
[ -z "$BAD_DEFAULT" ] && pass 'compose 文件无弱默认口令（${VAR:-值} 型兜底）' || fail "compose 存在弱默认口令：$BAD_DEFAULT"

echo "=== C. 数据库登录面 ==="
if MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -N -B -e "SELECT 1;" >/dev/null 2>&1; then
  pass "root 新口令（本地连接）可登录"
else
  fail "root 新口令连接失败"
fi
CUR=$(MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -h 127.0.0.1 -N -B -e "SELECT CURRENT_USER();" exam_prep 2>/dev/null)
[ "$CUR" = "root@localhost" ] && pass "TCP 127.0.0.1 认证为 root@localhost（后端 JDBC 路径）" || fail "TCP 127.0.0.1 认证结果异常: ${CUR:-<空>}"
# 负向断言：旧弱口令 123456 必须登录失败（这是"P0 已关闭"的核心证据）
if MYSQL_PWD=123456 "$MY" -uroot -e "SELECT 1;" >/dev/null 2>&1; then
  fail "弱口令 123456 仍可登录"
else
  pass "弱口令 123456 被拒绝"
fi
if MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -h "$LAN_IP" -e "SELECT 1;" >/dev/null 2>&1; then
  fail "局域网 IP($LAN_IP) 仍可连库"
else
  pass "局域网 IP($LAN_IP) 连库被拒绝"
fi
ROOTCNT=$(MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -N -B -e "SELECT COUNT(*) FROM mysql.user WHERE user='root';")
[ "$ROOTCNT" = "1" ] && pass "root 账户仅 1 个（远程 root 已删）" || fail "root 账户数 = $ROOTCNT"

echo "=== D. 监听面（重启 MySQL 服务后才变）==="
BIND=$(MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -N -B -e "SHOW VARIABLES LIKE 'bind_address';" | awk '{print $2}')
if [ "$BIND" = "127.0.0.1" ]; then
  pass "运行时 bind_address = 127.0.0.1"
else
  echo "  [WAIT] 运行时 bind_address = $BIND（my.ini 已改，待 Restart-Service mysql 生效）"
fi
grep -q '^bind-address=127.0.0.1' "$MYINI" && pass "my.ini bind-address=127.0.0.1" || fail "my.ini bind-address 未改"
grep -q '^mysqlx-bind-address=127.0.0.1' "$MYINI" && pass "my.ini mysqlx-bind-address=127.0.0.1" || fail "my.ini mysqlx-bind-address 未改"

echo "=== D2. 通用查询日志（会把明文口令抄到磁盘，必须关闭）==="
GLOG=$(MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -N -B -e "SHOW VARIABLES LIKE 'general_log';" | awk '{print $2}')
[ "$GLOG" = "OFF" ] && pass "运行时 general_log = OFF" || fail "general_log 仍为 $GLOG（会把 ALTER USER 的明文口令写进 mysql.log）"
grep -q '^general_log=0' "$MYINI" && pass "my.ini general_log=0（重启后仍保持关闭）" || fail "my.ini general_log 未改成 0"
GLOG_FILE=$(MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot -N -B -e "SHOW VARIABLES LIKE 'general_log_file';" | awk '{print $2}')
if [ -f "$GLOG_FILE" ]; then
  SZ=$(wc -c < "$GLOG_FILE" | tr -d ' ')
  LEAK=$(grep -c "IDENTIFIED BY" "$GLOG_FILE" 2>/dev/null)
  [ "$LEAK" = "0" ] && pass "通用日志中无 IDENTIFIED BY 明文（当前 ${SZ} 字节）" || fail "通用日志中仍有 $LEAK 条 IDENTIFIED BY 明文"
fi

echo "=== E. 依赖数据库的脚本 ==="
if "$DUMP" "--defaults-extra-file=config/mysql-client.local.cnf" --databases exam_prep --tables user 2>/dev/null | grep -q "INSERT INTO"; then
  pass "mysqldump --defaults-extra-file 免密导出成功"
else
  fail "mysqldump 免密导出失败"
fi
DRILL=$(cd 项目规划/第9周/evidence && bash backup_drill_20261109_counts.sh exam_prep /tmp/_week10_verify.tsv 2>&1 | tail -1)
case "$DRILL" in
  tables=*) pass "备份演练统计脚本：$DRILL" ;;
  *) fail "备份演练统计脚本异常：$DRILL" ;;
esac
rm -f /tmp/_week10_verify.tsv

echo "=== F. 超管数据 ==="
ROWS=$(MYSQL_PWD="$PWD_MYSQL" "$MY" -uroot exam_prep -N -B -e "SELECT COUNT(*) FROM user WHERE student_no='admin' AND password_hash LIKE '\$2a\$10\$%' AND LENGTH(password_hash)=60;")
[ "$ROWS" = "1" ] && pass "admin 密码哈希为 BCrypt(\$2a\$10\$, 60 位)" || fail "admin 哈希格式异常"

echo
echo "结果：PASS=$PASS_N FAIL=$FAIL_N"
[ "$FAIL_N" -eq 0 ] || exit 1
