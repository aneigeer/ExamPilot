# ============================================================
# 上架前自检（只读，不修改任何文件）
#
#   运行：powershell -ExecutionPolicy Bypass -File .\上架前检查.ps1
#
#   作用：把「上架前准备清单」里依赖人工记忆的项固化成一次可重复执行的闸门。
#         FAIL 项不解决不应提交应用商店；WARN 项需按提示人工确认。
# ============================================================
$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent   # 脚本已移入 scripts/，项目根在上一级
$fail = 0
$warn = 0

function Section($name) { Write-Host "`n== $name ==" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host ("  [ OK ]   " + $msg) -ForegroundColor Green }
function Warn($msg) { $script:warn++; Write-Host ("  [WARN]   " + $msg) -ForegroundColor Yellow }
function Bad($msg)  { $script:fail++; Write-Host ("  [FAIL]   " + $msg) -ForegroundColor Red }

# ---------- 1. 后端产物 ----------
Section '后端产物'
$jar = Get-ChildItem (Join-Path $root 'backend\target') -Filter '*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($jar) { Ok ("后端 JAR 已构建：" + $jar.Name + "（" + [math]::Round($jar.Length/1MB,1) + " MB）") }
else { Bad '未找到后端 JAR，请先执行：cd backend && mvn clean package' }

# ---------- 2. JWT 密钥 ----------
Section 'JWT 密钥（不得使用空值/示例值）'
if ($env:JWT_SECRET) { Ok '已通过环境变量 JWT_SECRET 注入' }
else {
    $localYml = Join-Path $root 'backend\src\main\resources\application-local.yml'
    $hit = if (Test-Path $localYml) {
        Select-String -Path $localYml -Pattern '^\s*secret:\s*\S+' -ErrorAction SilentlyContinue
    }
    if ($hit) { Ok '已在 application-local.yml 中配置 app.jwt.secret（该文件不入库）' }
    else { Bad '未配置 JWT_SECRET（环境变量或 application-local.yml）；未配置时后端会拒绝启动' }
}

# ---------- 3. 发布签名 ----------
Section 'Android 发布签名'
$ks = Join-Path $root 'student-app\keystore.properties'
if (Test-Path $ks) { Ok '已提供 student-app/keystore.properties（release 将使用正式密钥）' }
else { Bad '缺少 keystore.properties：当前 release 回退 debug 签名，不可用于上架' }

# ---------- 4. 明文流量收口 ----------
Section '网络明文流量（release 必须禁止）'
$mainNsc = Join-Path $root 'student-app\app\src\main\res\xml\network_security_config.xml'
if (Test-Path $mainNsc) {
    [xml]$doc = Get-Content -Raw -Encoding UTF8 $mainNsc
    $base = $doc.'network-security-config'.'base-config'.cleartextTrafficPermitted
    $domains = @($doc.'network-security-config'.'domain-config' |
                Where-Object { $_.cleartextTrafficPermitted -eq 'true' })
    if ($base -eq 'false' -and $domains.Count -eq 0) {
        Ok 'release 默认禁止明文（base-config cleartext=false，且无启用中的明文域名白名单）'
    } elseif ($base -eq 'false') {
        Warn ("release 禁止明文，但仍放行 " + $domains.Count + " 个明文域名，确认均为必要且已收敛")
    } else {
        Bad 'release 仍允许明文 HTTP，请把 main 下 network_security_config 的 cleartextTrafficPermitted 改为 false'
    }
} else { Bad '缺少 main 下的 network_security_config.xml' }
$dbgNsc = Join-Path $root 'student-app\app\src\debug\res\xml\network_security_config.xml'
if (Test-Path $dbgNsc) { Ok 'debug 变体已覆盖为允许明文（局域网联调不受影响）' }
else { Warn '缺少 debug 变体覆盖，真机/模拟器局域网联调可能连不上后端' }

# ---------- 5. HTTPS 反代 ----------
Section 'HTTPS 反向代理'
$nginx = Join-Path $root 'nginx.conf'
if ((Test-Path $nginx) -and (Select-String -Path $nginx -Pattern 'listen\s+443|ssl_certificate' -Quiet)) {
    Ok 'nginx.conf 已启用 TLS（发现 443/ssl_certificate）'
} else {
    Warn 'nginx.conf 仍为明文 HTTP；上架前请用 nginx-https.conf.example 替换并配置证书与域名'
}

# ---------- 6. 容器编排安全 ----------
Section 'Docker 编排'
$dc = Join-Path $root 'docker-compose.yml'
if (Test-Path $dc) {
    if (Select-String -Path $dc -Pattern 'JWT_SECRET:\s*"\$\{JWT_SECRET:\?' -Quiet) {
        Ok 'docker-compose 强制注入 JWT_SECRET（缺失即拒绝启动）'
    } else { Warn 'docker-compose 未强制 JWT_SECRET，请确认生产启动参数' }
    if (Select-String -Path $dc -Pattern 'MYSQL_ROOT_PASSWORD:\s*"\$\{MYSQL_ROOT_PASSWORD:-.+\}"' -Quiet) {
        Warn 'docker-compose 仍给 MYSQL_ROOT_PASSWORD 设了默认值，生产应改为强制注入'
    } else {
        Ok 'docker-compose 未保存 MySQL 口令默认值（强制环境变量注入）'
    }
} else { Warn '未找到 docker-compose.yml（如不使用容器部署可忽略）' }

# ---------- 7. 学生端安装包 ----------
Section '学生端安装包'
$apk = Get-ChildItem $root -Filter '备考知识库学生端-*.apk' -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) { Ok ("已存在：" + $apk.Name + "（" + [math]::Round($apk.Length/1MB,2) + " MB，" + $apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm') + "）") }
else { Bad '根目录未找到学生端 APK，请执行 gradle assembleDebug/assembleRelease' }

# ---------- 8. 待人工确认项 ----------
Section '需人工确认（脚本无法判定）'
Warn 'CSP 未启用：管理端为零构建 Vue，需 script-src ''unsafe-eval''；启用前必须真机浏览器复验（见 SecurityConfig 注释）'
Warn '无障碍：TalkBack 真机朗读与大字模式布局需真机复验'
Warn 'App 真机功能回归（登录/刷题/上传/账号注销/数据导出）需真机点击验证'

# ---------- 汇总 ----------
Write-Host "`n================ 结论 ================" -ForegroundColor Cyan
Write-Host ("  FAIL 项：" + $fail + "   WARN 项：" + $warn)
if ($fail -gt 0) {
    Write-Host '  结论：未达上架条件，请先解决上述 FAIL 项。' -ForegroundColor Red
    exit 1
} else {
    Write-Host '  结论：代码与配置侧已就绪；WARN 项按提示人工确认后即可提交。' -ForegroundColor Green
    exit 0
}
