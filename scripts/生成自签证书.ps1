# ============================================================
#  生成局域网自签 TLS 证书（带 SAN，可直接被 Android 信任）
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File .\scripts\生成自签证书.ps1
#    powershell -ExecutionPolicy Bypass -File .\scripts\生成自签证书.ps1 -Ip 10.200.83.194
#    powershell -ExecutionPolicy Bypass -File .\scripts\生成自签证书.ps1 -Ip 10.200.83.194 -Force
#
#  产物（默认输出到 nginx/certs/，该目录已加入 .gitignore，证书不入库）：
#    server.crt  证书（PEM）
#    server.key  私钥（PEM）
#    server.p12  合并的 PKCS#12（给需要 keystore 的场景）
#    同时把 server.crt 复制到 student-app/app/src/main/res/raw/server_cert.pem，
#    由 network_security_config.xml 作为唯一信任锚点（自签场景必须显式信任）。
#
#  ★ 为什么必须带 SAN：Android 7+ 与 Chrome 已完全忽略 CN，只认 subjectAltName。
#    只写 CN 的证书在手机上会报 "NET::ERR_CERT_COMMON_NAME_INVALID"，
#    而 curl -k 又能通——非常容易误判成"证书没问题"。
#
#  ★ 换网络/IP 后必须重签并重装 App：证书 SAN 里写死了 IP，
#    换了办公网还沿用旧证书，App 会直接连接失败（而不是降级），这是预期行为。
# ============================================================
param(
    [string]$Ip = "",
    [string]$OutDir = "",
    [int]$Days = 825,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $root 'nginx\certs' }

# ---------- 0. 自动探测当前局域网 IP（未显式传 -Ip 时）----------
if ([string]::IsNullOrWhiteSpace($Ip)) {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixOrigin -ne 'WellKnown'
        } | Sort-Object -Property InterfaceIndex
    if ($candidates) {
        $Ip = ($candidates | Select-Object -First 1).IPAddress
        Write-Host "未指定 -Ip，自动使用当前局域网 IP：$Ip"
    } else {
        throw "未探测到局域网 IPv4，请显式传 -Ip"
    }
}

# ---------- 1. 定位 openssl ----------
# 依次尝试：PATH 中的 openssl → Git for Windows（含 WorkBuddy 自带的 PortableGit）
$openssl = $null
$candidates = @('openssl')
foreach ($gitRoot in @(
    $env:GIT_INSTALL_ROOT,
    'C:\Program Files\Git',
    'C:\Program Files (x86)\Git',
    'C:\workboddy\WorkBuddy\resources\vendor\PortableGit',
    'D:\bigplan\tools\PortableGit'
)) {
    if ($gitRoot) {
        $candidates += (Join-Path $gitRoot 'usr\bin\openssl.exe')
        $candidates += (Join-Path $gitRoot 'mingw64\bin\openssl.exe')
    }
}
$candidates += 'D:\bigplan\tools\openssl\bin\openssl.exe'
$candidates = $candidates | Where-Object { $_ } | Select-Object -Unique
foreach ($c in $candidates) {
    try {
        $cmd = Get-Command $c -ErrorAction Stop
        $openssl = $cmd.Source
        break
    } catch { }
}
if (-not $openssl) {
    throw "找不到 openssl。已尝试：$($candidates -join ' / ')。请安装 Git for Windows（自带 openssl）或 OpenSSL。"
}
Write-Host "openssl: $openssl"

# ---------- 2. 准备输出目录 ----------
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$crt = Join-Path $OutDir 'server.crt'
$key = Join-Path $OutDir 'server.key'
$p12 = Join-Path $OutDir 'server.p12'
if ((Test-Path $crt) -and -not $Force) {
    throw "证书已存在：$crt（换 IP 重签请加 -Force）"
}

# ---------- 3. 生成自签证书（SAN 同时写 localhost / 127.0.0.1 / 局域网 IP）----------
$san = "DNS:localhost,IP:127.0.0.1,IP:$Ip"
Write-Host "生成证书：SAN=$san  有效期=$Days 天"
# ★ PowerShell 5.1 会把原生命令写到 stderr 的进度信息当成 NativeCommandError，
#   在 $ErrorActionPreference='Stop' 下直接中断脚本（openssl 明明成功也会"失败"）。
#   因此调用原生程序期间临时放宽，改看 exit code。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $openssl req -x509 -newkey rsa:2048 -sha256 -nodes `
    -keyout $key -out $crt -days $Days `
    -subj "/C=CN/ST=Hunan/L=Changsha/O=ExamPrep/OU=Dev/CN=exam-prep-lan" `
    -addext "subjectAltName=$san" `
    -addext "basicConstraints=critical,CA:TRUE" `
    -addext "keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign" `
    -addext "extendedKeyUsage=serverAuth" 2>&1 | Out-Null
$reqRc = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($reqRc -ne 0 -or -not (Test-Path $crt)) { throw "openssl 生成证书失败（exit=$reqRc）" }

# ---------- 4. 导出 PKCS#12（可选产物，给 Java/keystore 场景）----------
$ErrorActionPreference = 'Continue'
& $openssl pkcs12 -export -inkey $key -in $crt -out $p12 -passout pass:changeit 2>&1 | Out-Null
$ErrorActionPreference = $prevEap

# ---------- 5. 复制给 Android（信任锚点必须随包分发）----------
$rawDir = Join-Path $root 'student-app\app\src\main\res\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
Copy-Item $crt (Join-Path $rawDir 'server_cert.pem') -Force

# ---------- 6. 打印 SAN（验收用）----------
Write-Host ""
Write-Host "==== 证书信息（验收请贴这一段）====" -ForegroundColor Cyan
$ErrorActionPreference = 'Continue'
& $openssl x509 -in $crt -noout -subject -dates
& $openssl x509 -in $crt -noout -ext subjectAltName
$ErrorActionPreference = $prevEap
Write-Host ""
Write-Host "产物：" -ForegroundColor Cyan
Write-Host "  $crt"
Write-Host "  $key"
Write-Host "  $p12   (口令 changeit)"
Write-Host "  $(Join-Path $rawDir 'server_cert.pem')"
Write-Host ""
Write-Host "下一步：" -ForegroundColor Cyan
Write-Host "  1) nginx -c <项目根>/nginx-https.conf -t   先校验配置"
Write-Host "  2) nginx -c <项目根>/nginx-https.conf      启动（443）"
Write-Host "  3) curl -k -I https://$Ip/                应 200/301"
Write-Host "  4) 重新打 APK（BASE_URL 已指向 https://$Ip/api/）"
