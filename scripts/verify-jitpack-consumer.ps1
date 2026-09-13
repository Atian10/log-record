#Requires -Version 7.0
<#
远程发布接入验证入口。首次请求可能触发 JitPack 构建并公开制品；取消客户端不能撤销服务端构建。
仅在单独获准远程阶段后调用；不运行应用、业务测试、数据库、设备、模拟器或安装任务。
每次使用新的 RunId。所有生成工程、缓存、临时签名和证据限定在 build/verification 内。
#>
[CmdletBinding()]
param(
    # 必须显式确认远程请求的构建发布副作用；默认路径在任何目录或网络操作之前失败。
    [switch]$AllowRemoteBuild,
    [string]$Version,
    [string]$ExpectedCommit,
    # 文档面向候选标签；精确 SHA 轮的实际依赖仍只使用 Version。
    [string]$DocumentationVersion,
    [string]$JavaHome = 'E:\Java\temurin-11',
    [string]$SdkDirectory = 'E:\AndroidDev\Sdk',
    [string]$RunId = ('jitpack-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
)
if (!$AllowRemoteBuild) { throw '未启用 AllowRemoteBuild：已在任何网络请求或目录生成之前停止。首次依赖请求可能触发远程发布。' }
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Version -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$' -or $Version.Contains('..') -or $Version -match 'SNAPSHOT$') {
    throw 'Version 必须是明确的安全标签或提交版本，禁止路径字符、动态版本和 SNAPSHOT。'
}
if ($ExpectedCommit -notmatch '^[a-fA-F0-9]{40}$') { throw 'ExpectedCommit 必须是预期源码的完整 40 位提交 SHA。' }
if ($DocumentationVersion -cnotmatch '^v[0-9]+\.[0-9]+\.[0-9]+-rc\.[1-9][0-9]*$') { throw 'DocumentationVersion 必须是明确的候选标签版本。' }
if ($RunId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,99}$') { throw 'RunId 只能包含字母、数字和连字符。' }
$ExpectedCommit = $ExpectedCommit.ToLowerInvariant()
if ($Version -cne $ExpectedCommit -and $Version -cne $DocumentationVersion) { throw 'Version 只能是 ExpectedCommit 或 DocumentationVersion，不请求其他版本。' }
# 本轮只使用固定仓库，不接受任意远端、凭据或额外 Gradle 参数。
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runRoot = [IO.Path]::GetFullPath((Join-Path $workspace "build/verification/$RunId"))
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$group = 'com.github.Atian10.log-record'
$modules = @('log-core','log-desktop','log-android')
$commands = [Collections.Generic.List[object]]::new()
$downloads = [Collections.Generic.List[object]]::new()
$checks = [Collections.Generic.List[object]]::new()
$remoteArtifacts = @{}
# 单次运行内缓存精确提交的原始输入；快照列表另外保留工作区换行差异的证据。
$frozenSources = @{}
$snapshotEntries = [Collections.Generic.List[object]]::new()

function Assert-NoReparsePoint([string]$Path) {
    # 从目标追溯到卷根，阻止已有联接或符号链接改变实际写入位置。
    $absolute = [IO.Path]::GetFullPath($Path)
    $ancestor = $absolute
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "路径包含重解析点：$ancestor" }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $absolute
}

function Assert-RunPath([string]$Path) {
    # 所有写入及证据回读都必须属于当前唯一运行目录。
    $absolute = Assert-NoReparsePoint $Path
    if (!$absolute.StartsWith($runRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "本轮路径越界：$absolute" }
    return $absolute
}

function Write-RunText([string]$Path, [string]$Text) {
    $absolute = Assert-RunPath $Path
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($absolute)) | Out-Null
    [IO.File]::WriteAllText($absolute, $Text, $utf8)
}

function Write-RunJson([string]$Name, [object]$Value) {
    Write-RunText (Join-Path $runRoot $Name) (ConvertTo-Json -InputObject $Value -Depth 30)
}

function Get-RequiredHash([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -eq 0) { throw "缺少非空必要文件：$Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Invoke-RemoteDownload([string]$RelativeUri, [string]$RelativeOutput, [switch]$RequireAbsent) {
    # 每次请求总计最多十分钟；逐个记录 HTTPS 重定向，保存首错和已下载的失败证据。
    $destination = Assert-RunPath (Join-Path $runRoot $RelativeOutput)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    $uri = [uri]("https://jitpack.io/$RelativeUri")
    $record = [ordered]@{ RequestedUri=$uri.AbsoluteUri; StartedUtc=[DateTime]::UtcNow; CompletedUtc=$null; Redirects=@(); FinalUri=$null; Status=$null; Expectation=if ($RequireAbsent) { 'HTTP404Absence' } else { 'HTTP200Artifact' }; Path=$destination; Sha256=$null; Error=$null }
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [Threading.Timeout]::InfiniteTimeSpan
    $cancellation = [Threading.CancellationTokenSource]::new(600000)
    $response = $null
    $file = $null
    $contentStream = $null
    try {
        for ($redirect = 0; $redirect -le 5; $redirect++) {
            if ($uri.Scheme -ne 'https' -or $uri.UserInfo) { throw '远端重定向必须继续使用无凭据 HTTPS。' }
            $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $uri)
            try {
                $task = $client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cancellation.Token)
                if (!$task.Wait(600000, $cancellation.Token)) { throw '远端响应超过截止。' }
                $response = $task.Result
            } finally { $request.Dispose() }
            $record.Status = [int]$response.StatusCode
            if ($record.Status -in @(301,302,303,307,308)) {
                if (!$response.Headers.Location -or $redirect -eq 5) { throw '远端重定向缺少地址或超过次数。' }
                $nextUri = [uri]::new($uri, $response.Headers.Location)
                $record.Redirects += [pscustomobject]@{ From=$uri.AbsoluteUri; To=$nextUri.AbsoluteUri; Status=$record.Status }
                $response.Dispose(); $response = $null; $uri = $nextUri
                continue
            }
            $record.FinalUri = $uri.AbsoluteUri
            if ($RequireAbsent) {
                # 缺席也是实测结果；权限错误、超时和服务故障绝不能冒充不存在。
                if ($record.Status -ne 404) { throw "远端 module 缺席检查必须得到 HTTP 404，实际 HTTP $($record.Status)：$RelativeUri" }
                $record.Path = $null
                return
            }
            if ($record.Status -ne 200) { throw "远端下载失败，HTTP $($record.Status)：$RelativeUri" }
            if ($response.Content.Headers.ContentLength -gt 134217728) { throw '远端制品超过 128 MiB 限制。' }
            $file = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            # 使用 .NET Core 3.1 已有的 Stream 重载，兼容 PowerShell 7.0。
            $streamTask = $response.Content.ReadAsStreamAsync()
            if (!$streamTask.Wait(600000, $cancellation.Token)) { throw '读取远端内容流超过截止。' }
            $contentStream = $streamTask.Result
            $copy = $contentStream.CopyToAsync($file, 81920, $cancellation.Token)
            if (!$copy.Wait(600000, $cancellation.Token)) { throw '远端内容下载超过截止。' }
            $file.Dispose(); $file = $null
            if ((Get-Item -LiteralPath $destination).Length -gt 134217728) { throw '远端制品超过 128 MiB 限制。' }
            $record.Sha256 = Get-RequiredHash $destination
            return $destination
        }
    } catch { $record.Error = $_.Exception.ToString(); throw }
    finally {
        $cancellation.Cancel()
        if ($contentStream) { $contentStream.Dispose() }
        if ($file) { $file.Dispose() }
        if ($response) { $response.Dispose() }
        $cancellation.Dispose(); $client.Dispose()
        $record.CompletedUtc = [DateTime]::UtcNow
        $downloads.Add([pscustomobject]$record)
        Write-RunJson 'downloads.json' @($downloads.ToArray())
    }
}

function Read-GitBytes([string[]]$Arguments) {
    # 仅读取指定提交对象；通过字节流接收 blob，避免 PowerShell 文本管道改变换行或编码。
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $gitPath; $startInfo.WorkingDirectory = $workspace
    $startInfo.UseShellExecute = $false; $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true; $startInfo.RedirectStandardError = $true
    foreach ($name in @('GIT_DIR','GIT_WORK_TREE','GIT_COMMON_DIR','GIT_INDEX_FILE','GIT_OBJECT_DIRECTORY','GIT_ALTERNATE_OBJECT_DIRECTORIES','GIT_REPLACE_REF_BASE','GIT_CONFIG','GIT_CONFIG_COUNT')) { [void]$startInfo.Environment.Remove($name) }
    $startInfo.Environment['GIT_CONFIG_NOSYSTEM'] = '1'
    $startInfo.Environment['GIT_CONFIG_GLOBAL'] = 'NUL'
    $startInfo.Environment['GIT_NO_REPLACE_OBJECTS'] = '1'
    $startInfo.Environment['GIT_OPTIONAL_LOCKS'] = '0'
    foreach ($argument in (@('--no-pager','--no-replace-objects','-C',$workspace) + $Arguments)) { $startInfo.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $startInfo
    $memory = [IO.MemoryStream]::new()
    $cancellation = [Threading.CancellationTokenSource]::new(120000)
    $started = $false; $failure = $null
    try {
        if (!$process.Start()) { throw '只读 Git 对象进程未启动。' }
        $started = $true
        $copy = $process.StandardOutput.BaseStream.CopyToAsync($memory,81920,$cancellation.Token)
        $errorRead = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(120000)) { throw '只读 Git 对象进程超过两分钟截止。' }
        if (!$copy.Wait(10000) -or !$errorRead.Wait(10000)) { throw '只读 Git 对象输出未在截止内排空。' }
        if ($process.ExitCode -ne 0) { throw "读取精确提交对象失败：$($errorRead.Result)" }
        if ($memory.Length -gt 4194304) { throw '只读 Git 对象输出超过 4 MiB 限制。' }
        return ,$memory.ToArray()
    } catch { $failure = $_; throw
    } finally {
        try {
            if ($started -and !$process.HasExited) { $process.Kill($true); if (!$process.WaitForExit(10000)) { throw '只读 Git 进程树未在回收截止内退出。' } }
        } catch { if (!$failure) { throw }; [Console]::Error.WriteLine($_.Exception.Message) }
        finally { $cancellation.Cancel(); $cancellation.Dispose(); $memory.Dispose(); $process.Dispose() }
    }
}

function Get-BytesHash([byte[]]$Bytes) {
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hasher.ComputeHash($Bytes)).Replace('-','') } finally { $hasher.Dispose() }
}

# Source 是仓库相对路径；只读目标提交并核对工作区，返回原始内容及来源身份，禁止混入未提交内容。
function Get-FrozenSource([string]$Source) {
    if ($frozenSources.ContainsKey($Source)) { return $frozenSources[$Source] }
    # reference/blob 限定到 ExpectedCommit；字节及路径随后用于独立比较 Git 对象和实际检出文件。
    $reference = $ExpectedCommit + ':' + $Source
    $blob = $utf8.GetString((Read-GitBytes @('rev-parse',$reference))).Trim()
    if ($blob -cnotmatch '^[a-f0-9]{40}$' -or $utf8.GetString((Read-GitBytes @('cat-file','-t',$blob))).Trim() -cne 'blob') { throw "发布输入不是普通 Git blob：$Source" }
    $bytes = Read-GitBytes @('cat-file','blob',$blob)
    $path = Assert-NoReparsePoint (Join-Path $workspace $Source)
    [void](Get-RequiredHash $path)
    $actualBytes = [IO.File]::ReadAllBytes($path)
    # 两个哈希分别对应原始来源和检出内容；仅 Wrapper JAR 按二进制处理，其余输入严格解码 UTF-8。
    $gitHash = Get-BytesHash $bytes
    $actualHash = Get-BytesHash $actualBytes
    $binary = $Source.EndsWith('.jar',[StringComparison]::Ordinal)
    $text = if ($binary) { $null } else { $utf8.GetString($bytes) }
    # Windows 检出可改变 CRLF；仅容许这一差异，原始 Git/工作区哈希分别保留。
    if ($actualHash -cne $gitHash) {
        if ($binary -or $utf8.GetString($actualBytes).Replace(([string][char]13 + [char]10),[string][char]10) -cne $text.Replace(([string][char]13 + [char]10),[string][char]10)) { throw "工作区输入不对应 ExpectedCommit：$Source" }
    }
    $snapshotEntries.Add([pscustomobject]@{ Source=$Source; GitBlob=$blob; SourceSha256=$gitHash; WorktreeSha256=$actualHash; LineEndingsDiffer=($actualHash -cne $gitHash) })
    # 缓存对象只持有提交内容，后续示例抽取不会再次从可变工作区取文本。
    $frozen = [pscustomobject]@{ Source=$Source; GitBlob=$blob; SourceSha256=$gitHash; Text=$text }
    $frozenSources[$Source] = $frozen
    return $frozen
}

# 将入口文档绑定到 DocumentationVersion；实际下载版本仍由 Version 单独控制，SHA 轮不会请求候选标签。
function Assert-DocumentationVersion {
    # 两个入口逐一检查唯一标记、全部模块坐标及精确 Release 链接，结果写入本轮检查集合。
    foreach ($source in @('README.md','docs/使用文档.md')) {
        $frozen = Get-FrozenSource $source
        $markers = [regex]::Matches($frozen.Text,'<!-- release-documentation-version: ([^ ]+) -->')
        if ($markers.Count -ne 1 -or $markers[0].Groups[1].Value -cne $DocumentationVersion) { throw "候选文档版本标记缺失、重复或不同：$source" }
        $coordinates = [regex]::Matches($frozen.Text,'com\.github\.Atian10\.log-record:(log-core|log-desktop|log-android):([^''"\s\x60]+)')
        # 按模块去重，既拒绝旧版本/占位坐标，也防止删除整个平台说明后空集合通过。
        $seenModules = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($coordinate in $coordinates) {
            if ($coordinate.Groups[2].Value -cne $DocumentationVersion) { throw "当前接入文档仍有不同版本或占位坐标：$source" }
            [void]$seenModules.Add($coordinate.Groups[1].Value)
        }
        foreach ($module in @('log-android','log-desktop')) { if (!$seenModules.Contains($module)) { throw "文档缺少平台接入坐标：$source/$module" } }
        if (!$frozen.Text.Contains("https://github.com/Atian10/log-record/releases/tag/$DocumentationVersion")) { throw "文档缺少精确版本 Release 入口：$source" }
        $checks.Add([pscustomobject]@{ Check='DocumentationVersion'; Source=$source; GitBlob=$frozen.GitBlob; SourceSha256=$frozen.SourceSha256; DocumentationVersion=$DocumentationVersion; RequestedArtifactVersion=$Version })
    }
}

# Module 决定原始示例清单；Class/Parameters 仅补齐编译外壳，FullClass 表示原文已包含完整类。
function Get-ExampleSpecs([string]$Module) {
    if ($Module -eq 'log-android') {
        return @(
            [pscustomobject]@{Source='docs/使用文档.md';Marker='android-init';Class='MyApp';Language='java';Parameters='';FullClass=$true},
            [pscustomobject]@{Source='docs/使用文档.md';Marker='android-manifest';Class='';Language='xml';Parameters='';FullClass=$false},
            [pscustomobject]@{Source='README.md';Marker='readme-android-init';Class='ReadmeExample';Language='java';Parameters='';FullClass=$false},
            [pscustomobject]@{Source='log-android/src/main/java/com/atian10/logrecord/android/AndroidLogInit.java';Marker='javadoc-init';Class='AndroidJavadocExample';Language='java';Parameters='Context context';FullClass=$false},
            [pscustomobject]@{Source='docs/使用文档.md';Marker='exception-query';Class='QueryExample';Language='java';Parameters='long startTime, long endTime';FullClass=$false},
            [pscustomobject]@{Source='docs/使用文档.md';Marker='android-export';Class='ExportExample';Language='java';Parameters='Context context';FullClass=$false}
        )
    }
    if ($Module -eq 'log-desktop') {
        return @([pscustomobject]@{Source='log-desktop/src/main/java/com/atian10/logrecord/desktop/DesktopLogInit.java';Marker='javadoc-init';Class='DesktopJavadocExample';Language='java';Parameters='';FullClass=$false})
    }
    return @()
}

# Spec 来自固定清单；从提交原文提取唯一示例，保留正文，不改写 API 调用或执行其中方法。
function Get-FrozenSnippet($Spec) {
    # source 持有冻结内容，matches/text 分别保存唯一抽取匹配及原始正文。
    $source = Get-FrozenSource $Spec.Source
    if ($Spec.Marker -eq 'javadoc-init') {
        $matches = [regex]::Matches($source.Text,'(?s)<pre>\s*\r?\n(.*?)\r?\n\s*\*\s*</pre>')
        if ($matches.Count -ne 1) { throw "Javadoc 初始化示例必须唯一：$($Spec.Source)" }
        $text = ([regex]::Replace($matches[0].Groups[1].Value,'(?m)^\s*\* ?','')).Trim()
    } else {
        # 三反引号与精确标记共同限定代码块；语言必须与清单声明一致。
        $fence = ([string][char]96) * 3
        $marker = '<!-- verification:' + $Spec.Marker + ' -->'
        if ([regex]::Matches($source.Text,[regex]::Escape($marker)).Count -ne 1) { throw "示例标记缺失或重复：$($Spec.Source)/$($Spec.Marker)" }
        $pattern = '(?s)' + [regex]::Escape($marker) + '\s*' + $fence + $Spec.Language + '\s*\r?\n(.*?)\r?\n' + $fence
        $matches = [regex]::Matches($source.Text,$pattern)
        if ($matches.Count -ne 1) { throw "标记后的示例围栏无效：$($Spec.Source)/$($Spec.Marker)" }
        $text = $matches[0].Groups[1].Value
    }
    if ([string]::IsNullOrWhiteSpace($text)) { throw '原始示例为空。' }
    return [pscustomobject]@{Source=$Spec.Source;Marker=$Spec.Marker;Language=$Spec.Language;GitBlob=$source.GitBlob;SourceSha256=$source.SourceSha256;Text=$text}
}

# 返回隔离消费者内的生成路径；Android 包目录必须与 namespace 和 MyApp 注册相同。
function Get-ExampleFile([string]$ConsumerRoot, [string]$Module, $Spec) {
    if ($Spec.Language -eq 'xml') { return Join-Path $ConsumerRoot 'src/main/AndroidManifest.xml' }
    # XML 沿用主 Manifest 路径；Java 以平台包目录组织，禁止不同示例覆盖同一类。
    $directory = if ($Module -eq 'log-android') { 'src/main/java/com/atian10/logrecord/remoteverification' } else { 'src/main/java' }
    return Join-Path $ConsumerRoot "$directory/$($Spec.Class).java"
}

# 在 ConsumerRoot 生成 Module 的原文示例和来源收据；只增加 imports、类和方法外壳，不运行初始化。
function New-FrozenExamples([string]$ConsumerRoot, [string]$Module) {
    # 通用 imports 让文档中的短类型名可直接编译；平台类型另外加入前缀。
    $imports = @'
import com.atian10.logrecord.core.*;
import com.atian10.logrecord.core.config.*;
import com.atian10.logrecord.core.model.*;
import com.atian10.logrecord.core.query.*;
import com.atian10.logrecord.core.export.*;
import java.io.File;
import java.util.List;
'@
    # 生成器统一使用 LF；records 保存每个示例的原文、来源和生成文件哈希。
    $nl = [string][char]10
    $records = @()
    # spec/snippet/target 分别是固定声明、提交原文及隔离输出，prefix/suffix 只包裹正文。
    foreach ($spec in @(Get-ExampleSpecs $Module)) {
        $snippet = Get-FrozenSnippet $spec
        $target = Get-ExampleFile $ConsumerRoot $Module $spec
        $prefix = ''; $suffix = ''
        if ($spec.Language -eq 'java') {
            $prefix = if ($Module -eq 'log-android') {
                'package com.atian10.logrecord.remoteverification;' + $nl + 'import android.app.Application;' + $nl + 'import android.content.Context;' + $nl + 'import com.atian10.logrecord.android.AndroidLogInit;' + $nl + $imports + $nl
            } else { $imports + $nl + 'import com.atian10.logrecord.desktop.DesktopLogInit;' + $nl }
            if (!$spec.FullClass) {
                # Android 片段允许原样使用 this；完整 MyApp 类不再重复包裹。
                $extends = if ($Module -eq 'log-android') { ' extends Application' } else { '' }
                $prefix += 'public final class ' + $spec.Class + $extends + ' {' + $nl + '    /** 仅编译原始示例；参数提供外部上下文，本验证不调用此方法。 */' + $nl + '    public void example(' + $spec.Parameters + ') {' + $nl
                $suffix = $nl + '    }' + $nl + '}' + $nl
            }
        }
        Write-RunText $target ($prefix + $snippet.Text + $suffix)
        $records += [pscustomobject]@{Source=$snippet.Source;Marker=$snippet.Marker;Language=$snippet.Language;GitBlob=$snippet.GitBlob;SourceSha256=$snippet.SourceSha256;Snippet=$snippet.Text;GeneratedFile=$target;GeneratedSha256=(Get-RequiredHash $target)}
    }
    Write-RunText (Join-Path $ConsumerRoot 'documentation-examples.json') (ConvertTo-Json -InputObject $records -Depth 6)
}

# Path 必须在当前验证目录；只读 class 魔数和 major version，返回哈希与未执行声明。
function Get-CompiledClassEvidence([string]$Path) {
    $path = Assert-RunPath $Path
    # 只读取 class 的八字节头部；55 对应 Java 11，不加载或实例化该类。
    $stream = [IO.File]::OpenRead($path)
    try {
        $header = [byte[]]::new(8)
        if ($stream.Read($header,0,8) -ne 8 -or [BitConverter]::ToString($header,0,4) -cne 'CA-FE-BA-BE' -or ([int]$header[6]*256+[int]$header[7]) -ne 55) { throw "消费者没有生成 Java 11 class：$path" }
    } finally { $stream.Dispose() }
    return [pscustomobject]@{Path=$path;Sha256=(Get-RequiredHash $path);Bytes=(Get-Item -LiteralPath $path).Length;Executed=$false}
}

# 对指定消费者逐项核对原文、生成收据和编译输出；XML 来源与 Java 编译证据分开记录。
function Assert-FrozenExamples([string]$ConsumerRoot, [string]$Mode, [string]$Module) {
    # 清单是预期集合，收据是生成时保存的证据；每个 Source/Marker 必须唯一匹配。
    $specs = @(Get-ExampleSpecs $Module)
    $records = @(Get-Content -LiteralPath (Join-Path $ConsumerRoot 'documentation-examples.json') -Raw | ConvertFrom-Json)
    if ($records.Count -ne $specs.Count) { throw '原始示例收据数量不同。' }
    foreach ($spec in $specs) {
        $matched = @($records | Where-Object { $_.Source -ceq $spec.Source -and $_.Marker -ceq $spec.Marker })
        if ($matched.Count -ne 1) { throw "原始示例收据缺失或重复：$Mode/$Module/$($spec.Marker)" }
        # 独立重取冻结片段及目标路径，避免只相信收据自述。
        $record = $matched[0]
        $snippet = Get-FrozenSnippet $spec
        $generated = Get-ExampleFile $ConsumerRoot $Module $spec
        if ($record.Language -cne $spec.Language -or $record.GitBlob -cne $snippet.GitBlob -or $record.SourceSha256 -cne $snippet.SourceSha256 -or $record.Snippet -cne $snippet.Text -or
            $record.GeneratedFile -cne $generated -or $record.GeneratedSha256 -cne (Get-RequiredHash $generated) -or !(Get-Content -LiteralPath $generated -Raw).Contains($snippet.Text)) { throw '示例来源或生成内容不对应冻结提交。' }
        if ($spec.Language -eq 'xml') {
            $checks.Add([pscustomobject]@{Check='DocumentationManifest';Mode=$Mode;Module=$Module;Source=$spec.Source;Marker=$spec.Marker;GitBlob=$snippet.GitBlob;SourceSha256=$snippet.SourceSha256;GeneratedFile=$generated;GeneratedSha256=$record.GeneratedSha256;Executed=$false})
        } else {
            # 检查编译器实际输出位置，并验证 class 为 Java 11；不会运行生成的方法。
            $classDirectory = if ($Module -eq 'log-android') { 'build/intermediates/javac/debug/classes/com/atian10/logrecord/remoteverification' } else { 'build/classes/java/main' }
            $compiled = Get-CompiledClassEvidence (Join-Path $ConsumerRoot "$classDirectory/$($spec.Class).class")
            $checks.Add([pscustomobject]@{Check='DocumentationCompilation';Mode=$Mode;Module=$Module;Source=$spec.Source;Marker=$spec.Marker;GitBlob=$snippet.GitBlob;SourceSha256=$snippet.SourceSha256;GeneratedFile=$generated;GeneratedSha256=$record.GeneratedSha256;CompiledClass=$compiled})
        }
    }
}

# 读取三种宿主策略的真实合并产物；省略必须无属性，明确 false 不能替代省略。
function Assert-RemoteManifestCases([string]$ConsumerRoot, [string]$Mode) {
    # records 保留合并证据；case 的 null 代表宿主未指定备份策略。
    $records = @()
    foreach ($case in @(@{Variant='backupTrue';Value='true'},@{Variant='backupFalse';Value='false'},@{Variant='backupOmitted';Value=$null})) {
        $path = Assert-RunPath (Join-Path $ConsumerRoot "build/intermediates/merged_manifest/$($case.Variant)/AndroidManifest.xml")
        [xml]$manifest = Get-Content -LiteralPath $path -Raw
        $application = $manifest.SelectSingleNode('/manifest/application')
        if (!$application) { throw '合并 Manifest 缺少 application。' }
        # 属性存在性、值与 Application 全名分别检查，避免空字符串或其他 Application 蒙混通过。
        $present = $application.HasAttribute('allowBackup','http://schemas.android.com/apk/res/android')
        $value = $application.GetAttribute('allowBackup','http://schemas.android.com/apk/res/android')
        $name = $application.GetAttribute('name','http://schemas.android.com/apk/res/android')
        if (($null -eq $case.Value -and $present) -or ($null -ne $case.Value -and (!$present -or $value -cne $case.Value)) -or $name -cne 'com.atian10.logrecord.remoteverification.MyApp') { throw "实际 Manifest 与宿主选择或 MyApp 注册不同：$Mode/$($case.Variant)" }
        # 同一条证据同时写入消费者收据和总检查集合，ApplicationExecuted 始终为 false。
        $record = [pscustomobject]@{Check='ConsumerManifest';Mode=$Mode;Module='log-android';Variant=$case.Variant;Expected=$case.Value;HasAllowBackup=$present;Actual=$value;Application=$name;Path=$path;Sha256=(Get-RequiredHash $path);ApplicationExecuted=$false}
        $records += $record
        $checks.Add($record)
    }
    Write-RunText (Join-Path $ConsumerRoot 'manifest-cases.json') (ConvertTo-Json -InputObject $records -Depth 5)
}

# Check 选取证据类别，Fields 定义身份字段；要求复合键集合完整且唯一，不以总数量代替覆盖检查。
function Assert-CheckSet([string]$Check, [string[]]$Fields, [string[]]$ExpectedKeys) {
    # records 是待验收记录，actual 保存区分大小写的复合键，拒绝重复后再检查预期集合。
    $records = @($checks | Where-Object { $_.Check -ceq $Check })
    $actual = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($record in $records) {
        $key = (@($Fields | ForEach-Object { [string]$record.$_ }) -join '|')
        if (!$actual.Add($key)) { throw "验收项重复：$Check/$key" }
    }
    if ($records.Count -ne $ExpectedKeys.Count) { throw "验收项集合大小不同：$Check" }
    foreach ($key in $ExpectedKeys) { if (!$actual.Contains($key)) { throw "缺少必需验收项：$Check/$key" } }
}

# 汇总冻结来源、九项制品、六消费者及示例/Manifest 证据；任何缺项均不能报告整轮通过。
function Assert-CompleteMatrix {
    Assert-CheckSet 'SourceIdentity' @('ExpectedCommit') @($ExpectedCommit)
    Assert-CheckSet 'DocumentationVersion' @('Source') @('README.md','docs/使用文档.md')
    foreach ($check in @('RemotePublication','MetadataAbsent','SourceContents')) { Assert-CheckSet $check @('Module') $modules }
    Assert-CheckSet 'ConsumerBom' @('Mode') @('DefaultMaven','PomOnly')
    Assert-CheckSet 'LibraryBackupPolicyAbsent' @('Module') @('log-android')
    # 以解析模式和固定模块/示例声明展开预期身份，避免同一消费者的重复记录填补缺项。
    $consumerKeys = @(); $javaKeys = @(); $xmlKeys = @(); $manifestKeys = @()
    foreach ($mode in @('DefaultMaven','PomOnly')) {
        foreach ($module in $modules) {
            $consumerKeys += "$mode|$module"
            foreach ($spec in @(Get-ExampleSpecs $module)) {
                $key = "$mode|$module|$($spec.Source)|$($spec.Marker)"
                if ($spec.Language -eq 'java') { $javaKeys += $key } else { $xmlKeys += $key }
            }
        }
        foreach ($variant in @('backupTrue','backupFalse','backupOmitted')) { $manifestKeys += "$mode|$variant" }
    }
    Assert-CheckSet 'Consumer' @('Mode','Module') $consumerKeys
    Assert-CheckSet 'DocumentationCompilation' @('Mode','Module','Source','Marker') $javaKeys
    Assert-CheckSet 'DocumentationManifest' @('Mode','Module','Source','Marker') $xmlKeys
    Assert-CheckSet 'CoreApiCompilation' @('Mode') @('DefaultMaven','PomOnly')
    Assert-CheckSet 'ConsumerManifest' @('Mode','Variant') $manifestKeys
    if ($javaKeys.Count -ne 12 -or $xmlKeys.Count -ne 2) { throw '六段 Java 示例与 XML 清单定义不完整。' }
}

function Assert-SourceContents($Zip, [string]$Module, [string]$ArchivePath) {
    $prefix = "$Module/src/main/java/"
    $treeText = $utf8.GetString((Read-GitBytes @('ls-tree','-r','-z',$ExpectedCommit,'--',"$Module/src/main/java")))
    $expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($item in $treeText.Split([char]0, [StringSplitOptions]::RemoveEmptyEntries)) {
        if ($item -cnotmatch '^100(?:644|755) blob ([a-f0-9]{40})\t(.+)$') { throw "精确提交含不支持的源条目：$Module" }
        $blob = $Matches[1]; $path = $Matches[2]
        if (!$path.StartsWith($prefix,[StringComparison]::Ordinal)) { throw 'Git 源文件路径超出指定模块。' }
        $expected.Add($path.Substring($prefix.Length),$blob)
    }
    if ($expected.Count -eq 0) { throw "精确提交没有主源码：$Module" }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $sources = [Collections.Generic.List[object]]::new()
    $generated = [Collections.Generic.List[string]]::new()
    foreach ($entry in $Zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        if (!$seen.Add($entry.FullName)) { throw "源码包条目重复：$Module/$($entry.FullName)" }
        if (!$expected.ContainsKey($entry.FullName)) {
            # 仅允许已知包装条目；任何额外生成源码必须先另列来源，不能自动接受。
            if ($entry.FullName -ceq 'META-INF/MANIFEST.MF' -or $entry.FullName -ceq "META-INF/log-record/$Module/LICENSE") { $generated.Add($entry.FullName); continue }
            throw "源码包有精确提交之外的条目，需核对其来源：$Module/$($entry.FullName)"
        }
        if ($entry.Length -gt 1048576) { throw '单个源码条目超过 1 MiB 限制。' }
        $blob = $expected[$entry.FullName]
        $sizeText = $utf8.GetString((Read-GitBytes @('cat-file','-s',$blob))).Trim()
        if ($sizeText -notmatch '^\d+$' -or [long]$sizeText -gt 1048576) { throw '单个 Git 源对象大小异常。' }
        $gitBytes = Read-GitBytes @('cat-file','blob',$blob)
        $stream = $entry.Open(); $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $sourceBytes = $memory.ToArray() } finally { $memory.Dispose(); $stream.Dispose() }
        $gitHash = Get-BytesHash $gitBytes; $sourceHash = Get-BytesHash $sourceBytes
        if ($gitBytes.Length -ne $sourceBytes.Length -or $gitHash -cne $sourceHash) { throw "源码条目字节与精确提交不同：$Module/$($entry.FullName)" }
        $sources.Add([pscustomobject]@{ Entry=$entry.FullName; GitPath=$prefix+$entry.FullName; GitBlob=$blob; Bytes=$sourceBytes.Length; GitContentSha256=$gitHash; SourceContentSha256=$sourceHash })
    }
    $missing = @($expected.Keys | Where-Object { !$seen.Contains($_) })
    if ($missing.Count) { throw "源码包缺少精确提交条目：$Module/$($missing -join ',')" }
    $receipt = [pscustomobject]@{ Check='SourceContents'; Module=$Module; ExpectedCommit=$ExpectedCommit; ArchiveSha256=(Get-RequiredHash $ArchivePath); Entries=@($sources.ToArray()); PackagingEntries=@($generated.ToArray()); State='PASSED' }
    Write-RunJson "remote/$Module/source-contents.json" $receipt
    $checks.Add($receipt)
}

function Invoke-ConsumerProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$ConsumerRoot) {
    # 直接使用参数数组启动进程；仅回收此进程树，不扫描或终止其他 Java/Gradle 进程。
    $stdoutPath = Assert-RunPath (Join-Path $runRoot "logs/$Name.stdout.log")
    $stderrPath = Assert-RunPath (Join-Path $runRoot "logs/$Name.stderr.log")
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Executable; $startInfo.WorkingDirectory = $ConsumerRoot
    $startInfo.UseShellExecute = $false; $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true; $startInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
    # 清除能注入 JVM 参数或共享缓存的入口；不输出继承环境或读取凭据。
    foreach ($environmentName in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS','GRADLE_RO_DEP_CACHE')) {
        [void]$startInfo.Environment.Remove($environmentName)
    }
    $startInfo.Environment['JAVA_HOME'] = $JavaHome
    $startInfo.Environment['ANDROID_HOME'] = $SdkDirectory
    $startInfo.Environment['ANDROID_SDK_ROOT'] = $SdkDirectory
    $startInfo.Environment['ANDROID_USER_HOME'] = Join-Path $ConsumerRoot 'android-user'
    $startInfo.Environment['GRADLE_USER_HOME'] = Join-Path $ConsumerRoot 'gradle-home'
    $startInfo.Environment['USERPROFILE'] = Join-Path $ConsumerRoot 'user-home'
    $startInfo.Environment['HOME'] = Join-Path $ConsumerRoot 'user-home'
    $startInfo.Environment['TEMP'] = Join-Path $ConsumerRoot 'tmp'
    $startInfo.Environment['TMP'] = Join-Path $ConsumerRoot 'tmp'
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $startInfo
    $cancellation = [Threading.CancellationTokenSource]::new()
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    $started = $false; $timedOut = $false; $exitCode = $null; $failure = $null
    $stdoutFile = $null; $stderrFile = $null; $stdoutCopy = $null; $stderrCopy = $null
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    try {
        $stdoutFile = [IO.File]::Create($stdoutPath); $stderrFile = [IO.File]::Create($stderrPath)
        Write-Output "START $Name"
        if (!$process.Start()) { throw "进程未启动：$Name" }
        $started = $true
        $stdoutCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdoutFile, 81920, $cancellation.Token)
        $stderrCopy = $process.StandardError.BaseStream.CopyToAsync($stderrFile, 81920, $cancellation.Token)
        while (!$process.WaitForExit(1000)) {
            if ($elapsed.ElapsedMilliseconds -ge 1800000) { $timedOut = $true; throw "进程超过三十分钟截止：$Name" }
        }
        $exitCode = $process.ExitCode
        $drainTime = [int][Math]::Max(0, [Math]::Min(10000, 1800000 - $elapsed.ElapsedMilliseconds))
        $copies = [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($stdoutCopy,$stderrCopy))
        if ($drainTime -eq 0 -or !$copies.Wait($drainTime)) { $timedOut = $true; throw "日志排空超过截止：$Name" }
        if ($exitCode -ne 0) { throw "进程失败：$Name，退出码 $exitCode" }
    } catch { $failure = $_.Exception }
    finally {
        try {
            if ($started -and !$process.HasExited) { $process.Kill($true); if (!$process.WaitForExit(10000)) { throw '本轮进程树未在截止后退出。' } }
            if ($started -and $process.HasExited) { $exitCode = $process.ExitCode }
        } catch { $cleanupErrors.Add($_.Exception.ToString()) }
        try { $cancellation.Cancel() } catch { $cleanupErrors.Add($_.Exception.ToString()) }
        if ($started) {
            try { $process.StandardOutput.Dispose(); $process.StandardError.Dispose() } catch { $cleanupErrors.Add($_.Exception.ToString()) }
        }
        foreach ($copyTask in @($stdoutCopy,$stderrCopy)) {
            if ($null -ne $copyTask) {
                try { if (!$copyTask.Wait(1000)) { $cleanupErrors.Add('日志复制取消后仍未退出。') } }
                catch { if (!$failure) { $cleanupErrors.Add($_.Exception.ToString()) } }
            }
        }
        foreach ($resource in @($stdoutFile,$stderrFile,$cancellation,$process)) {
            if ($null -ne $resource) { try { $resource.Dispose() } catch { $cleanupErrors.Add($_.Exception.ToString()) } }
        }
        if (!$failure -and $cleanupErrors.Count -gt 0) { $failure = [InvalidOperationException]::new($cleanupErrors[0]) }
        $commands.Add([pscustomobject]@{ Name=$Name; Started=$started; ExitCode=$exitCode; TimedOut=$timedOut; ElapsedMilliseconds=$elapsed.ElapsedMilliseconds; Arguments=$Arguments; Stdout=$stdoutPath; Stderr=$stderrPath; Error=if ($failure) { $failure.ToString() } else { $null }; CleanupErrors=@($cleanupErrors.ToArray()) })
        try { Write-RunJson 'commands.json' @($commands.ToArray()) }
        catch { [Console]::Error.WriteLine($_.Exception); if (!$failure) { $failure = $_.Exception } }
    }
    if ($failure) { throw $failure }
    Write-Output "PASS $Name"
}

function Read-ZipEntryText($Zip, [string]$Name) {
    # 只读档案内容；不解压到源码目录，也不加载任何应用类。
    $entry = $Zip.GetEntry($Name)
    if (!$entry -or $entry.Length -eq 0 -or $entry.Length -gt 1048576) { throw "必要归档条目缺失或大小异常：$Name" }
    $reader = [IO.StreamReader]::new($entry.Open(), $utf8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Assert-License($Zip, [string]$Module) {
    $licenseName = "META-INF/log-record/$Module/LICENSE"
    if (@($Zip.Entries | Where-Object { $_.FullName -ceq $licenseName }).Count -ne 1) { throw "许可证条目缺失或重复：$Module" }
    $text = Read-ZipEntryText $Zip $licenseName
    if (($text -replace "`r`n","`n").Trim() -cne $licenseText) { throw "许可证内容不一致：$Module" }
}

function Assert-JavaClasses($Zip, [string]$Module) {
    # 检查每个项目 class 的 magic 和 major 55；空 JAR 不能作为通过证据。
    $count = 0
    foreach ($entry in $Zip.Entries) {
        if (!$entry.FullName.EndsWith('.class')) { continue }
        $stream = $entry.Open()
        try {
            $header = [byte[]]::new(8); $offset = 0
            while ($offset -lt 8) { $read = $stream.Read($header,$offset,8-$offset); if ($read -eq 0) { throw 'class 头不完整。' }; $offset += $read }
            if ([BitConverter]::ToString($header,0,4) -ne 'CA-FE-BA-BE' -or ([int]$header[6]*256+[int]$header[7]) -ne 55) { throw "字节码不是 Java 11：$Module/$($entry.FullName)" }
            $count++
        } finally { $stream.Dispose() }
    }
    if ($count -eq 0) { throw "没有项目 class：$Module" }
    return $count
}

function Assert-RemotePublication([string]$Module) {
    # 新发布契约为 Maven POM；缺席的 module 与无重定向 marker 必须有本轮证据。
    $files = $remoteArtifacts[$Module]
    $pomText = Get-Content -LiteralPath $files.pom -Raw
    if ($pomText -match 'published-with-gradle-metadata') { throw "远端 POM 仍含 Gradle metadata 重定向 marker：$Module" }
    [xml]$pom = $pomText
    if ($pom.project.groupId -cne $group -or $pom.project.artifactId -cne $Module -or $pom.project.version -cne $Version) { throw "远端 POM 坐标不同：$Module" }
    $packagingNode = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='packaging']")
    $packaging = if ($packagingNode) { $packagingNode.InnerText } else { 'jar' }
    $expectedPackaging = if ($Module -eq 'log-android') { 'aar' } else { 'jar' }
    if ($packaging -cne $expectedPackaging) { throw "远端 POM 制品类型不同：$Module" }
    if ([string]::IsNullOrWhiteSpace([string]$pom.project.name) -or [string]::IsNullOrWhiteSpace([string]$pom.project.description) -or
        $pom.project.url -cne 'https://github.com/Atian10/log-record' -or $pom.project.scm.url -cne 'https://github.com/Atian10/log-record' -or
        $pom.project.scm.connection -cne 'scm:git:https://github.com/Atian10/log-record.git') { throw "远端 POM 名称、描述或源码地址不符合约定：$Module" }
    $licenses = @($pom.SelectNodes("/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license']"))
    if ($licenses.Count -ne 1 -or $licenses[0].name -notmatch 'MIT' -or $licenses[0].url -notmatch '^https://') { throw "POM 缺少 MIT 许可元数据：$Module" }
    $expected = switch ($Module) {
        'log-core' { @('com.google.code.gson:gson:2.10.1:compile') }
        'log-desktop' { @("${group}:log-core:${Version}:compile",'org.xerial:sqlite-jdbc:3.42.0.0:runtime') }
        'log-android' { @("${group}:log-core:${Version}:compile",'androidx.room:room-runtime:2.5.2:runtime','androidx.annotation:annotation:1.6.0:runtime','org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0:runtime','org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0:runtime') }
    }
    $actual = @($pom.project.dependencies.dependency | ForEach-Object { "$($_.groupId):$($_.artifactId):$($_.version):$($_.scope)" })
    if (@(Compare-Object ($expected | Sort-Object) ($actual | Sort-Object)).Count) { throw "远端 POM 依赖不同：$Module" }
    if ($Module -eq 'log-android') {
        $bomNodes = @($pom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencyManagement']/*[local-name()='dependencies']/*[local-name()='dependency']"))
        if ($bomNodes.Count -ne 1) { throw '远端 Android POM 必须具有唯一 Kotlin BOM 导入项。' }
        $bom = $bomNodes[0]
        if ($bom.groupId -ne 'org.jetbrains.kotlin' -or $bom.artifactId -ne 'kotlin-bom' -or $bom.version -ne '1.8.0' -or $bom.scope -ne 'import' -or $bom.type -ne 'pom') { throw '远端 POM 未传递 Kotlin 1.8.0 BOM。' }
    }
    $sourceZip = [IO.Compression.ZipFile]::OpenRead($files.sources)
    try {
        Assert-License $sourceZip $Module
        if (@($sourceZip.Entries | Where-Object { $_.FullName.EndsWith('.java') }).Count -eq 0) { throw "源码 JAR 为空：$Module" }
        Assert-SourceContents $sourceZip $Module $files.sources
    } finally { $sourceZip.Dispose() }
    $zip = [IO.Compression.ZipFile]::OpenRead($files.binary)
    try {
        if ($Module -eq 'log-android') {
            if ((Read-ZipEntryText $zip 'AndroidManifest.xml') -notmatch 'minSdkVersion="21"') { throw '远端 AAR minSdk 不是 21。' }
            # 检查实际 AAR 内的应用属性；无 application 节点同样表示库未指定备份策略。
            [xml]$libraryManifest = Read-ZipEntryText $zip 'AndroidManifest.xml'
            $libraryApplication = $libraryManifest.SelectSingleNode('/manifest/application')
            if ($libraryApplication -and $libraryApplication.HasAttribute('allowBackup','http://schemas.android.com/apk/res/android')) { throw '远端 AAR 不得决定宿主 allowBackup。' }
            $checks.Add([pscustomobject]@{Check='LibraryBackupPolicyAbsent';Module=$Module;BinarySha256=(Get-RequiredHash $files.binary);HasAllowBackup=$false})
            if ((Read-ZipEntryText $zip 'proguard.txt') -notmatch 'com\.atian10\.logrecord\.android\.room\.\*\*') { throw '远端 AAR 缺少 Room consumer rules。' }
            $entry = $zip.GetEntry('classes.jar')
            if (!$entry -or $entry.Length -gt 134217728) { throw 'AAR 的 classes.jar 缺失或过大。' }
            $stream = $entry.Open(); $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory); $memory.Position = 0
                $classes = [IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read,$true)
                try { Assert-License $classes $Module; $classCount = Assert-JavaClasses $classes $Module } finally { $classes.Dispose() }
            } finally { $memory.Dispose(); $stream.Dispose() }
        } else { Assert-License $zip $Module; $classCount = Assert-JavaClasses $zip $Module }
    } finally { $zip.Dispose() }
    $checks.Add([pscustomobject]@{ Check='RemotePublication'; Module=$Module; MetadataPolicy='MavenPom'; Packaging=$packaging; Classes=$classCount; PomSha256=(Get-RequiredHash $files.pom); BinarySha256=(Get-RequiredHash $files.binary); SourcesArchiveSha256=(Get-RequiredHash $files.sources) })
}

function New-Consumer([string]$Mode, [string]$Module) {
    # 每个消费者拥有空缓存和独立源码，不引用本仓库项目、mavenLocal 或任何文件仓库。
    $consumer = Assert-RunPath (Join-Path $runRoot "consumers/$Mode-$Module")
    foreach ($directory in @('gradle-home','user-home','m2','tmp','android-user','project-cache')) { [IO.Directory]::CreateDirectory((Join-Path $consumer $directory)) | Out-Null }
    if ($Mode -notin @('DefaultMaven','PomOnly')) { throw '未知的 Maven POM 消费模式。' }
    # 默认路径完全省略 metadataSources，保留 Gradle 对 POM marker 的正常处理。
    $metadataSources = if ($Mode -eq 'DefaultMaven') { '' } else { 'metadataSources { mavenPom(); ignoreGradleMetadataRedirection() }' }
    $settings = @'
rootProject.name = '__NAME__'
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri('https://jitpack.io'); __METADATA__ } }
            filter { includeGroup 'com.github.Atian10.log-record' }
        }
    }
}
'@
    Write-RunText (Join-Path $consumer 'settings.gradle') ($settings.Replace('__NAME__',"$Mode-$Module").Replace('__METADATA__',$metadataSources))
    $preamble = if ($Module -eq 'log-android') {
@'
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath 'com.android.tools.build:gradle:7.4.2' }
}
apply plugin: 'com.android.application'
android {
    namespace 'com.atian10.logrecord.remoteverification'
    compileSdk 33
    defaultConfig { applicationId 'com.atian10.logrecord.remoteverification'; minSdk 21; targetSdk 33; versionCode 1; versionName 'verification' }
    compileOptions { sourceCompatibility JavaVersion.VERSION_11; targetCompatibility JavaVersion.VERSION_11 }
    // 调试签名位于当前消费者目录，Release 保持未签名。
    signingConfigs { debug { storeFile file('android-user/debug.keystore') } }
    buildTypes {
        backupTrue { initWith debug; matchingFallbacks = ['debug'] }
        backupFalse { initWith debug; matchingFallbacks = ['debug'] }
        backupOmitted { initWith debug; matchingFallbacks = ['debug'] }
        release { minifyEnabled true; proguardFiles getDefaultProguardFile('proguard-android-optimize.txt') }
    }
}
'@
    } else {
@'
apply plugin: 'java'
java { sourceCompatibility JavaVersion.VERSION_11; targetCompatibility JavaVersion.VERSION_11 }
tasks.withType(JavaCompile).configureEach { options.release = 11 }
'@
    }
    $build = @'
if (gradle.gradleVersion != '7.5' || JavaVersion.current() != JavaVersion.VERSION_11) { throw new GradleException('固定 Gradle 7.5 / Java 11。') }
dependencies { implementation 'com.github.Atian10.log-record:__MODULE__:__VERSION__' }
// 显式解析 sources，连同二进制一起与独立 HTTPS 下载的哈希比较。
configurations { verificationSources { canBeConsumed = false; canBeResolved = true; transitive = false } }
dependencies { verificationSources 'com.github.Atian10.log-record:__MODULE__:__VERSION__:sources@jar' }
tasks.register('verifyRuntimeGraph') {
    doLast {
        def reports = []
        def namesToResolve = __CONFIGURATIONS__ + ['verificationSources']
        namesToResolve.each { configurationName ->
            def configuration = configurations.getByName(configurationName)
            configuration.incoming.resolutionResult.allComponents.each { component ->
                if (component.id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier && component.id != configuration.incoming.resolutionResult.root.id) { throw new GradleException('禁止项目依赖替代。') }
            }
            def artifacts = configuration.resolvedConfiguration.resolvedArtifacts
            def names = artifacts.collect { it.name }
            if (configurationName != 'verificationSources') {
                def required = __REQUIRED__
                assert names.containsAll(required) : names
                assert !names.any { it in __FORBIDDEN__ } : names
                def projectModules = artifacts.findAll { it.moduleVersion.id.group == 'com.github.Atian10.log-record' }.collect { it.name }.toSet()
                assert projectModules == __PROJECT_MODULES__.toSet() : projectModules
                if ('__MODULE__' == 'log-android') {
                    assert names.any { it in ['annotation','annotation-jvm'] } : names
                    def kotlin = artifacts.findAll { it.moduleVersion.id.group == 'org.jetbrains.kotlin' && it.name.startsWith('kotlin-stdlib') }
                    assert !kotlin.empty
                    assert kotlin.every { it.moduleVersion.id.version == '1.8.0' } : kotlin
                }
            }
            artifacts.each { artifact ->
                def id = artifact.moduleVersion.id
                def expectedExternalVersions = ['com.google.code.gson:gson':'2.10.1', 'org.xerial:sqlite-jdbc':'3.42.0.0', 'androidx.room:room-runtime':'2.5.2', 'androidx.room:room-common':'2.5.2', 'androidx.annotation:annotation':'1.6.0', 'androidx.annotation:annotation-jvm':'1.6.0']
                def externalKey = "${id.group}:${id.name}".toString()
                if (expectedExternalVersions.containsKey(externalKey)) { assert id.version == expectedExternalVersions[externalKey] : id }
                if (id.group == 'org.jetbrains.kotlin' && id.name.startsWith('kotlin-stdlib')) { assert id.version == '1.8.0' : id }
                if (id.group == 'com.github.Atian10.log-record') {
                    assert id.version == '__VERSION__'
                    assert id.name in __PROJECT_MODULES__
                    assert artifact.file.canonicalFile.toPath().startsWith(new File(gradle.gradleUserHomeDir,'caches/modules-2/files-2.1').canonicalFile.toPath()) : artifact.file
                }
                def digest = java.security.MessageDigest.getInstance('SHA-256').digest(artifact.file.bytes).encodeHex().toString()
                reports << [configuration:configurationName, group:id.group, module:id.name, version:id.version, classifier:artifact.classifier, extension:artifact.extension, path:artifact.file.canonicalPath, sha256:digest]
            }
        }
        assert !reports.empty
        file('resolved-artifacts.json').text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(reports))
        println 'PASS remote-runtime-graph'
    }
}
gradle.taskGraph.whenReady { graph ->
    // 即使未来任务依赖变化，也拒绝测试、应用运行、安装、设备或发布任务。
    graph.allTasks.each { task ->
        if (task instanceof Test || task instanceof JavaExec || task.name ==~ /(?i)(.*test.*|connected.*|managedDevice.*|install.*|uninstall.*|run.*|publish.*)/) { throw new GradleException("禁止任务：${task.path}") }
    }
}
'@
    $required = switch ($Module) { 'log-core' { "['log-core','gson']" }; 'log-desktop' { "['log-desktop','log-core','gson','sqlite-jdbc']" }; 'log-android' { "['log-android','log-core','gson','room-runtime']" } }
    $forbidden = switch ($Module) { 'log-core' { "['log-desktop','log-android','sqlite-jdbc','room-runtime']" }; 'log-desktop' { "['log-android','room-runtime']" }; 'log-android' { "['log-desktop','sqlite-jdbc']" } }
    $projectModules = if ($Module -eq 'log-core') { "['log-core']" } else { "['$Module','log-core']" }
    $configurations = if ($Module -eq 'log-android') { "['debugCompileClasspath','debugRuntimeClasspath','releaseCompileClasspath','releaseRuntimeClasspath']" } else { "['compileClasspath','runtimeClasspath']" }
    # Android implementation 依赖可能只位于 runtime，因此 compile 图仅用于取证，完整要求在 runtime 图断言。
    $build = $build.Replace("if (configurationName != 'verificationSources')", "if (configurationName != 'verificationSources' && !configurationName.toLowerCase().endsWith('compileclasspath'))")
    $build = $build.Replace('__MODULE__',$Module).Replace('__VERSION__',$Version).Replace('__REQUIRED__',$required).Replace('__FORBIDDEN__',$forbidden).Replace('__PROJECT_MODULES__',$projectModules).Replace('__CONFIGURATIONS__',$configurations)
    Write-RunText (Join-Path $consumer 'build.gradle') ($preamble + "`n" + $build)
    Write-RunText (Join-Path $consumer 'gradle.properties') "android.useAndroidX=true`norg.gradle.java.installations.auto-download=false`norg.gradle.java.installations.auto-detect=false`nandroid.builder.sdkDownload=false`n"
    if ($Module -eq 'log-android') {
        Write-RunText (Join-Path $consumer 'local.properties') ('sdk.dir=' + $SdkDirectory.Replace('\','/'))
        New-FrozenExamples $consumer $Module
        # 仅 true/false 用例添加宿主覆盖；省略用例使用原始主 Manifest，不生成 allowBackup。
        foreach ($case in @(@{Variant='backupTrue';Value='true'},@{Variant='backupFalse';Value='false'})) {
            $overlay = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:allowBackup="' + $case.Value + '" /></manifest>'
            Write-RunText (Join-Path $consumer "src/$($case.Variant)/AndroidManifest.xml") $overlay
        }
    } else {
        $source = if ($Module -eq 'log-desktop') { 'public static void configure(String path) { com.atian10.logrecord.desktop.DesktopLogInit.init(path, new com.atian10.logrecord.core.config.LogConfig.Builder()); }' } else { 'public static com.atian10.logrecord.core.config.LogConfig.Builder configure() { return new com.atian10.logrecord.core.config.LogConfig.Builder(); }' }
        Write-RunText (Join-Path $consumer 'src/main/java/Consumer.java') ("/** 仅供编译检查，不运行初始化。 */`npublic final class Consumer { /** 核对公开 API 与传递依赖。 */ $source }")
    }
    if ($Module -eq 'log-desktop') { New-FrozenExamples $consumer $Module }
    if ($Module -eq 'log-core') {
        # 参数式源码只让 javac 核对公开签名；不创建控制台替身、不提供真实日志或调用该方法。
        $proof = @'
import com.atian10.logrecord.core.IConsoleOutput;
import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.model.ExceptionRecord;
/** 仅编译公开签名；不实例化 Fake、不运行初始化或输出方法。 */
public final class ConsoleApiExample {
    /** 参数仅用于匹配 init/print 的公开类型与重载；本验证不调用此方法。 */
    public static void signatures(LogConfig config, IConsoleOutput output,
            LogRecord log, ExceptionRecord exception, IFormatter formatter) {
        LogManager.init(config, output);
        output.print(log, formatter);
        output.print(exception, formatter);
    }
}
'@
        # 保存源码输出及脚本 blob，使核心签名证据能够回溯到同一个冻结提交。
        $proofPath = Join-Path $consumer 'src/main/java/ConsoleApiExample.java'
        Write-RunText $proofPath $proof
        Write-RunText (Join-Path $consumer 'core-api-example.json') (ConvertTo-Json -InputObject ([pscustomobject]@{Source=$proofPath;Sha256=(Get-RequiredHash $proofPath);ScriptGitBlob=(Get-FrozenSource 'scripts/verify-jitpack-consumer.ps1').GitBlob;Executed=$false}))
    }
    return $consumer
}

function Invoke-ConsumerGradle([string]$Name, [string]$ConsumerRoot, [string[]]$Tasks) {
    # JVM 主类使用仓库的官方 Wrapper；不加载本地验证 init 或用户 init 脚本。
    $daemonArguments = '-Dfile.encoding=UTF-8 -Duser.home="' + (Join-Path $ConsumerRoot 'user-home').Replace('\','/') + '" -Djava.io.tmpdir="' + (Join-Path $ConsumerRoot 'tmp').Replace('\','/') + '"'
    $arguments = @('-Dfile.encoding=UTF-8',('-Duser.home=' + (Join-Path $ConsumerRoot 'user-home')),('-Djava.io.tmpdir=' + (Join-Path $ConsumerRoot 'tmp')),
        '-classpath',(Join-Path $workspace 'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain',
        '--no-daemon','--no-parallel','--no-build-cache','--no-configuration-cache','--console=plain','--info','--stacktrace',
        '-g',(Join-Path $ConsumerRoot 'gradle-home'),'-p',$ConsumerRoot,'--project-cache-dir',(Join-Path $ConsumerRoot 'project-cache'),
        ('-Dorg.gradle.java.home=' + $JavaHome),('-Dorg.gradle.jvmargs=' + $daemonArguments),('-Dmaven.repo.local=' + (Join-Path $ConsumerRoot 'm2')),
        '-Dorg.gradle.internal.http.connectionTimeout=180000','-Dorg.gradle.internal.http.socketTimeout=600000') + $Tasks
    Invoke-ConsumerProcess $Name (Join-Path $JavaHome 'bin/java.exe') $arguments $ConsumerRoot
}

function Assert-ConsumerBom([string]$ConsumerRoot, [string]$Mode) {
    # BOM 是解析 POM 时读取的依赖约束文件，不添加到应用依赖，也不伪装为 JAR。
    $cache = Assert-RunPath (Join-Path $ConsumerRoot 'gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-bom/1.8.0')
    $files = @(Get-ChildItem -LiteralPath $cache -Recurse -File -Filter 'kotlin-bom-1.8.0.pom')
    if ($files.Count -ne 1) { throw '实际消费缓存缺少唯一 Kotlin BOM 1.8.0 POM。' }
    $bomPath = Assert-RunPath $files[0].FullName
    [xml]$bom = Get-Content -LiteralPath $bomPath -Raw
    if ($bom.project.groupId -cne 'org.jetbrains.kotlin' -or $bom.project.artifactId -cne 'kotlin-bom' -or $bom.project.version -cne '1.8.0' -or $bom.project.packaging -cne 'pom') { throw '实际读取的 Kotlin BOM POM 身份不同。' }
    $kotlinVersionNode = $bom.SelectSingleNode("/*[local-name()='project']/*[local-name()='properties']/*[local-name()='kotlin.version']")
    if (!$kotlinVersionNode) { throw 'Kotlin BOM 缺少 kotlin.version 属性。' }
    $kotlinVersion = $kotlinVersionNode.InnerText.Replace('${project.version}','1.8.0')
    if ($kotlinVersion -cne '1.8.0') { throw 'Kotlin BOM 属性未对齐 1.8.0。' }
    $constraints = @($bom.project.dependencyManagement.dependencies.dependency | Where-Object { $_.artifactId -like 'kotlin-stdlib*' })
    if ($constraints.Count -eq 0) { throw '实际 BOM 未提供标准库约束。' }
    foreach ($constraint in $constraints) {
        $constraintGroup = ([string]$constraint.groupId).Replace('${project.groupId}','org.jetbrains.kotlin')
        $constraintVersion = ([string]$constraint.version).Replace('${kotlin.version}',$kotlinVersion).Replace('${project.version}','1.8.0')
        if ($constraintGroup -cne 'org.jetbrains.kotlin' -or $constraintVersion -cne '1.8.0') { throw '实际 BOM 标准库约束不符合 1.8.0。' }
    }
    foreach ($name in @('kotlin-stdlib','kotlin-stdlib-jdk7','kotlin-stdlib-jdk8')) { if ($name -cnotin @($constraints.artifactId)) { throw "实际 BOM 缺少 $name 约束。" } }
    $logPath = Assert-RunPath (Join-Path $runRoot "logs/$Mode-log-android-build.stdout.log")
    $logText = Get-Content -LiteralPath $logPath -Raw
    $bomDownloadMatches = [regex]::Matches($logText,'(?m)^Downloading (https://[^\s]+/org/jetbrains/kotlin/kotlin-bom/1\.8\.0/kotlin-bom-1\.8\.0\.pom)(?:\s|$)')
    $sourceUris = @($bomDownloadMatches | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if ($sourceUris.Count -ne 1) { throw '日志未证明实际 BOM POM 的唯一下载来源。' }
    $sourceUri = [uri]$sourceUris[0]
    if ($sourceUri.UserInfo -or $sourceUri.Host -notin @('repo.maven.apache.org','repo1.maven.org','dl.google.com','maven.google.com')) { throw 'BOM 来源不属于消费者声明的仓库。' }
    $receipt = [pscustomobject]@{ Check='ConsumerBom'; Mode=$Mode; Group='org.jetbrains.kotlin'; Module='kotlin-bom'; Version='1.8.0'; Packaging='pom'; SourceUri=$sourceUri.AbsoluteUri; Path=$bomPath; Sha256=(Get-RequiredHash $bomPath); Log=$logPath; Constraints=@($constraints.artifactId) }
    Write-RunJson "consumers/$Mode-log-android/bom-evidence.json" $receipt
    $checks.Add($receipt)
}

function Assert-ConsumerOutputs([string]$ConsumerRoot, [string]$Mode, [string]$Module) {
    # 报告同时绑定编译/打包输出内容，不能只凭依赖图文件证明本轮消费完成。
    $outputPaths = [Collections.Generic.List[string]]::new()
    if ($Module -eq 'log-core') {
        # proof/source/class 分别核对生成收据、源码与实际编译输出，三者必须对应同一运行。
        $proof = Get-Content -LiteralPath (Join-Path $ConsumerRoot 'core-api-example.json') -Raw | ConvertFrom-Json
        $source = Join-Path $ConsumerRoot 'src/main/java/ConsoleApiExample.java'
        if ($proof.Source -cne $source -or $proof.Sha256 -cne (Get-RequiredHash $source) -or $proof.Executed -ne $false -or
            $proof.ScriptGitBlob -cne (Get-FrozenSource 'scripts/verify-jitpack-consumer.ps1').GitBlob) { throw '核心公开签名示例身份不同。' }
        $class = Get-CompiledClassEvidence (Join-Path $ConsumerRoot 'build/classes/java/main/ConsoleApiExample.class')
        $checks.Add([pscustomobject]@{Check='CoreApiCompilation';Mode=$Mode;Module=$Module;Source=$source;SourceSha256=$proof.Sha256;ScriptGitBlob=$proof.ScriptGitBlob;CompiledClass=$class})
        $outputPaths.Add((Join-Path $ConsumerRoot 'core-api-example.json'))
    } else {
        Assert-FrozenExamples $ConsumerRoot $Mode $Module
        $outputPaths.Add((Join-Path $ConsumerRoot 'documentation-examples.json'))
    }
    $reportPath = Join-Path $ConsumerRoot 'resolved-artifacts.json'
    [void](Get-RequiredHash $reportPath)
    $outputPaths.Add($reportPath)
    $report = @(Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json)
    $projectEntries = @($report | Where-Object { $_.group -ceq $group })
    if ($projectEntries.Count -eq 0) { throw '依赖图没有远程项目制品。' }
    foreach ($entry in $projectEntries) {
        $path = Assert-RunPath $entry.path
        $kind = if ($entry.classifier -eq 'sources') { 'sources' } else { 'binary' }
        if ($entry.version -cne $Version -or !$remoteArtifacts.ContainsKey($entry.module) -or
            (Get-RequiredHash $path) -ine (Get-RequiredHash $remoteArtifacts[$entry.module][$kind]) -or
            $entry.sha256 -ine (Get-RequiredHash $path)) { throw '消费方实际原始包与独立远端下载内容不同。' }
    }
    # 两条路径都必须实际使用同一 POM，默认路径不能被 marker 转向未知 module。
    foreach ($resolvedModule in @($projectEntries.module | Sort-Object -Unique)) {
        $cache = Join-Path $ConsumerRoot "gradle-home/caches/modules-2/files-2.1/$group/$resolvedModule/$Version"
        $metadataFiles = @(Get-ChildItem -LiteralPath $cache -Recurse -File -Filter "$resolvedModule-$Version.pom")
        if ($metadataFiles.Count -ne 1 -or (Get-RequiredHash $metadataFiles[0].FullName) -ine (Get-RequiredHash $remoteArtifacts[$resolvedModule].pom)) { throw '实际解析的 POM 与远端原始元数据不同或缺失。' }
        if ((Get-Content -LiteralPath $metadataFiles[0].FullName -Raw) -match 'published-with-gradle-metadata' -or @(Get-ChildItem -LiteralPath $cache -Recurse -File -Filter '*.module').Count -ne 0) { throw '消费者实际缓存不符合无 module/marker 的 POM 发布契约。' }
        $outputPaths.Add($metadataFiles[0].FullName)
    }
    if ($Module -eq 'log-android') {
        Assert-RemoteManifestCases $ConsumerRoot $Mode
        $outputPaths.Add((Join-Path $ConsumerRoot 'manifest-cases.json'))
        Assert-ConsumerBom $ConsumerRoot $Mode
        $outputPaths.Add((Join-Path $ConsumerRoot 'bom-evidence.json'))
        foreach ($variant in @('debug','release')) {
            $apks = @(Get-ChildItem -LiteralPath (Join-Path $ConsumerRoot "build/outputs/apk/$variant") -Filter '*.apk' -File)
            if ($apks.Count -ne 1) { throw "未生成唯一 $variant APK。" }
            $outputPaths.Add($apks[0].FullName)
            $zip = [IO.Compression.ZipFile]::OpenRead($apks[0].FullName)
            try {
                if (!$zip.GetEntry('classes.dex') -or !$zip.GetEntry('AndroidManifest.xml')) { throw 'APK 内容不完整。' }
                Assert-License $zip 'log-core'; Assert-License $zip 'log-android'
            } finally { $zip.Dispose() }
        }
        [void](Get-RequiredHash (Join-Path $ConsumerRoot 'build/outputs/mapping/release/mapping.txt'))
        $outputPaths.Add((Join-Path $ConsumerRoot 'build/outputs/mapping/release/mapping.txt'))
        $outputPaths.Add((Join-Path $ConsumerRoot 'build/outputs/mapping/release/configuration.txt'))
        $configuration = Get-Content -LiteralPath (Join-Path $ConsumerRoot 'build/outputs/mapping/release/configuration.txt') -Raw
        if ($configuration -notmatch 'com\.atian10\.logrecord\.android\.room\.\*\*') { throw 'R8 最终配置未包含传递的 Room 规则。' }
    } else { $outputPaths.Add((Join-Path $ConsumerRoot 'build/classes/java/main/Consumer.class')) }
    $outputs = @($outputPaths | ForEach-Object {
        $output = Assert-RunPath $_
        [pscustomobject]@{ Path=$output; Sha256=(Get-RequiredHash $output); Bytes=(Get-Item -LiteralPath $output).Length }
    })
    $checks.Add([pscustomobject]@{ Check='Consumer'; Mode=$Mode; Module=$Module; Report=$reportPath; Outputs=$outputs })
}

# 前置核对仅使用显式工具路径；不安装或查找其他目录中的 JDK、SDK、密钥。
[void](Assert-NoReparsePoint $runRoot)
if (Test-Path -LiteralPath $runRoot) { throw 'RunId 已存在，禁止重用旧工程、缓存或报告。' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$SdkDirectory = (Resolve-Path -LiteralPath $SdkDirectory).Path
$gitPath = (Get-Command git -CommandType Application -ErrorAction Stop).Source
if ([IO.Path]::GetFullPath($PSCommandPath) -cne [IO.Path]::GetFullPath((Join-Path $workspace 'scripts/verify-jitpack-consumer.ps1'))) { throw '必须使用冻结提交中的仓库脚本入口。' }
if ($utf8.GetString((Read-GitBytes @('cat-file','-t',$ExpectedCommit))).Trim() -cne 'commit' -or $utf8.GetString((Read-GitBytes @('rev-parse','HEAD'))).Trim() -cne $ExpectedCommit) { throw 'HEAD 必须为 ExpectedCommit 的精确提交。' }
if ($Version -ceq $DocumentationVersion -and $utf8.GetString((Read-GitBytes @('rev-parse',("refs/tags/$Version" + '^{commit}')))).Trim() -cne $ExpectedCommit) { throw '标签未指向 ExpectedCommit。' }
# 列明影响发布契约及生成示例的源码输入；逐一匹配 S，随后将工作区原始哈希纳入结束复查。
$sourcePaths = @(
    'scripts/verify-jitpack-consumer.ps1','README.md','CHANGELOG.md','docs/使用文档.md',
    'docs/公开发布说明.md','docs/日志记录库-架构设计文档.md','docs/日志记录库-功能设计文档.md',
    'build.gradle','settings.gradle','gradle.properties','jitpack.yml','LICENSE','gradlew',
    'gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties',
    'log-core/build.gradle','log-desktop/build.gradle','log-android/build.gradle',
    'log-android/src/main/AndroidManifest.xml',
    'log-android/src/main/java/com/atian10/logrecord/android/AndroidLogInit.java',
    'log-desktop/src/main/java/com/atian10/logrecord/desktop/DesktopLogInit.java',
    'log-core/src/main/java/com/atian10/logrecord/core/IConsoleOutput.java'
)
foreach ($sourcePath in $sourcePaths) { [void](Get-FrozenSource $sourcePath) }
Assert-DocumentationVersion
# 在下载前确认每个原始示例唯一且非空；消费者只编译这些冻结内容。
foreach ($module in @('log-android','log-desktop')) { foreach ($spec in @(Get-ExampleSpecs $module)) { [void](Get-FrozenSnippet $spec) } }
if ((Get-FrozenSource 'build.gradle').Text -notmatch 'com\.android\.tools\.build:gradle:7\.4\.2') { throw '冻结提交必须保留 AGP 7.4.2。' }
# 源码与实际工具共同进入运行前后哈希检查；任何一项发生变化都使当前证据失效。
$inputPaths = @($sourcePaths | ForEach-Object { Join-Path $workspace $_ }) + @(
    (Join-Path $JavaHome 'release'),(Join-Path $JavaHome 'bin/java.exe'),(Join-Path $JavaHome 'bin/keytool.exe'),(Join-Path $SdkDirectory 'platforms/android-33/android.jar'),$gitPath)
$inputs = @($inputPaths | ForEach-Object { [pscustomobject]@{ Path=$_; Sha256=(Get-RequiredHash $_) } })
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch 'JAVA_VERSION="11\.') { throw '必须使用已有 JDK 11。' }
$wrapperProperties = Get-Content -LiteralPath (Join-Path $workspace 'gradle/wrapper/gradle-wrapper.properties') -Raw
if ($wrapperProperties -notmatch '(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-7\.5-all\.zip\s*$' -or $wrapperProperties -notmatch '(?m)^distributionSha256Sum=[a-fA-F0-9]{64}\s*$') { throw 'Wrapper 必须固定官方 Gradle 7.5 并具备分发校验值。' }
$licenseText = ((Get-Content -LiteralPath (Join-Path $workspace 'LICENSE') -Raw) -replace "`r`n","`n").Trim()
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $runRoot 'logs')) | Out-Null
# 分别记录请求制品版本、源码文档版本及 v2 验收定义，禁止将旧矩阵收据解释成新增检查通过。
$state = [ordered]@{ State='RUNNING'; MetadataPolicy='MavenPom'; ConsumerModes=@('DefaultMaven','PomOnly'); DefinitionVersion='pom-publication-v2'; Version=$Version; DocumentationVersion=$DocumentationVersion; ExpectedCommit=$ExpectedCommit; StartedUtc=[DateTime]::UtcNow; Inputs=$inputs; SourceInputs=@($snapshotEntries.ToArray()); Error=$null; RemoteCancellation='客户端取消或失败不代表服务端停止，也不撤销已发布制品。' }
Write-RunJson 'source-inputs.json' @($snapshotEntries.ToArray())
$checks.Add([pscustomobject]@{Check='SourceIdentity';ExpectedCommit=$ExpectedCommit;DocumentationVersion=$DocumentationVersion;SourceInputs=@($snapshotEntries.ToArray())})
Write-RunJson 'run.json' $state
$remoteLog = $null
try {
    foreach ($module in $modules) {
        $remoteArtifacts[$module] = @{}
        $extension = if ($module -eq 'log-android') { 'aar' } else { 'jar' }
        foreach ($kind in @('pom','binary','sources')) {
            $suffix = switch ($kind) { 'pom' { '.pom' }; 'binary' { ".$extension" }; 'sources' { '-sources.jar' } }
            $fileName = "$module-$Version$suffix"
            $remoteArtifacts[$module][$kind] = Invoke-RemoteDownload "com/github/Atian10/log-record/$module/$Version/$fileName" "remote/$module/$fileName"
        }
        Invoke-RemoteDownload "com/github/Atian10/log-record/$module/$Version/$module-$Version.module" "remote/$module/$module-$Version.module" -RequireAbsent
        $checks.Add([pscustomobject]@{ Check='MetadataAbsent'; Module=$module; ExpectedStatus=404; MetadataPolicy='MavenPom' })
        Assert-RemotePublication $module
    }
    # 构建日志来自同一版本，要求精确源码身份及真实工具输出；命令回显不能替代结果。
    $remoteLog = Invoke-RemoteDownload "com/github/Atian10/log-record/$Version/build.log" 'remote/build.log'
    $logText = (Get-Content -LiteralPath $remoteLog -Raw) -replace '\x1B\[[0-?]*[ -/]*[@-~]',''
    if ($logText -notmatch ('(?m)^JITPACK_PROVENANCE_COMMIT=' + [regex]::Escape($ExpectedCommit) + '\s*$') -or
        $logText -notmatch ('(?m)^JITPACK_PROVENANCE_VERSION=' + [regex]::Escape($Version) + '\s*$') -or
        $logText -notmatch '(?m)^Gradle 7\.5\s*$' -or $logText -notmatch '(?m)^JVM:\s+11[.\s]' -or $logText -notmatch 'BUILD SUCCESSFUL') { throw '远程日志未证明预期提交、版本和 Gradle 7.5/JVM 11 成功构建。' }
    foreach ($module in $modules) { if ($logText -notmatch ([regex]::Escape(":${module}:publishToMavenLocal"))) { throw "远程日志缺少 $module 发布任务。" } }
    foreach ($mode in @('DefaultMaven','PomOnly')) {
        foreach ($module in $modules) {
            $consumer = New-Consumer $mode $module
            Invoke-ConsumerGradle "$mode-$module-version" $consumer @('--version')
            if ($module -eq 'log-android') {
                # 仅生成本轮临时调试签名，不读取个人或正式签名。
                $keyArguments = @(('-J-Duser.home=' + (Join-Path $consumer 'user-home')),'-genkeypair','-noprompt','-keystore',(Join-Path $consumer 'android-user/debug.keystore'),'-storepass','android','-alias','androiddebugkey','-keypass','android','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-validity','30')
                Invoke-ConsumerProcess "$mode-$module-key" (Join-Path $JavaHome 'bin/keytool.exe') $keyArguments $consumer
                Invoke-ConsumerGradle "$mode-$module-build" $consumer @('assembleDebug','assembleRelease','processBackupTrueMainManifest','processBackupFalseMainManifest','processBackupOmittedMainManifest','verifyRuntimeGraph')
            } else { Invoke-ConsumerGradle "$mode-$module-build" $consumer @('classes','verifyRuntimeGraph') }
            Assert-ConsumerOutputs $consumer $mode $module
            Write-RunJson 'checks.json' @($checks.ToArray())
        }
    }
    foreach ($input in $inputs) { if ((Get-RequiredHash $input.Path) -cne $input.Sha256) { throw '验证期间脚本、工具或基准许可证发生变化。' } }
    Assert-CompleteMatrix
    $state.State = 'PASSED'
} catch {
    $firstFailure = $_
    $state.State = 'FAILED'; $state.Error = $_.Exception.ToString()
    # 首次制品请求失败时也尝试保留远端日志；诊断失败不能覆盖原失败或标记通过。
    if (!$remoteLog -and $downloads.Count -gt 0 -and !(Test-Path -LiteralPath (Join-Path $runRoot 'remote/build.log'))) {
        try { $remoteLog = Invoke-RemoteDownload "com/github/Atian10/log-record/$Version/build.log" 'remote/build.log' }
        catch { $state['RemoteLogError'] = $_.Exception.ToString() }
    }
    throw $firstFailure
}
finally { Write-RunJson 'checks.json' @($checks.ToArray()); Write-RunJson 'run.json' $state }
Write-Output "COMPLETED $runRoot"
