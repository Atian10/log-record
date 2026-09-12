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
if ($RunId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,99}$') { throw 'RunId 只能包含字母、数字和连字符。' }
$ExpectedCommit = $ExpectedCommit.ToLowerInvariant()
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

function Invoke-RemoteDownload([string]$RelativeUri, [string]$RelativeOutput) {
    # 每次请求总计最多十分钟；逐个记录 HTTPS 重定向，保存首错和已下载的失败证据。
    $destination = Assert-RunPath (Join-Path $runRoot $RelativeOutput)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    $uri = [uri]("https://jitpack.io/$RelativeUri")
    $record = [ordered]@{ RequestedUri=$uri.AbsoluteUri; Redirects=@(); FinalUri=$null; Status=$null; Path=$destination; Sha256=$null; Error=$null }
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
        $downloads.Add([pscustomobject]$record)
        Write-RunJson 'downloads.json' @($downloads.ToArray())
    }
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
    # 同时检查远端 POM、Module Metadata、源代码及二进制，避免 HTTP 200 的错误页冒充制品。
    $files = $remoteArtifacts[$Module]
    [xml]$pom = Get-Content -LiteralPath $files.pom -Raw
    if ($pom.project.groupId -cne $group -or $pom.project.artifactId -cne $Module -or $pom.project.version -cne $Version) { throw "远端 POM 坐标不同：$Module" }
    if ([string]::IsNullOrWhiteSpace([string]$pom.project.name) -or [string]::IsNullOrWhiteSpace([string]$pom.project.description) -or
        $pom.project.url -cne 'https://github.com/Atian10/log-record' -or $pom.project.scm.url -cne 'https://github.com/Atian10/log-record' -or
        $pom.project.scm.connection -cne 'scm:git:https://github.com/Atian10/log-record.git') { throw "远端 POM 名称、描述或源码地址不符合约定：$Module" }
    $licenses = @($pom.SelectNodes("/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license']"))
    if ($licenses.Count -ne 1 -or $licenses[0].name -notmatch 'MIT' -or $licenses[0].url -notmatch '^https://') { throw "POM 缺少 MIT 许可元数据：$Module" }
    $expected = switch ($Module) {
        'log-core' { @('com.google.code.gson:gson:2.10.1:compile') }
        'log-desktop' { @("${group}:log-core:${Version}:compile",'org.xerial:sqlite-jdbc:3.42.0.0:runtime') }
        'log-android' { @("${group}:log-core:${Version}:compile",'androidx.room:room-runtime:2.5.2:runtime','androidx.annotation:annotation:1.6.0:runtime') }
    }
    $actual = @($pom.project.dependencies.dependency | ForEach-Object { "$($_.groupId):$($_.artifactId):$($_.version):$($_.scope)" })
    if (@(Compare-Object ($expected | Sort-Object) ($actual | Sort-Object)).Count) { throw "远端 POM 依赖不同：$Module" }
    if ($Module -eq 'log-android') {
        $bom = $pom.project.dependencyManagement.dependencies.dependency
        if ($bom.groupId -ne 'org.jetbrains.kotlin' -or $bom.artifactId -ne 'kotlin-bom' -or $bom.version -ne '1.8.0' -or $bom.scope -ne 'import' -or $bom.type -ne 'pom') { throw '远端 POM 未传递 Kotlin 1.8.0 BOM。' }
    }
    $metadata = Get-Content -LiteralPath $files.module -Raw | ConvertFrom-Json -AsHashtable
    if ($metadata.component.group -cne $group -or $metadata.component.module -cne $Module -or $metadata.component.version -cne $Version) { throw "远端 module 坐标不同：$Module" }
    foreach ($usage in @('java-api','java-runtime')) {
        $variants = @($metadata.variants | Where-Object { $_.attributes['org.gradle.usage'] -eq $usage -and $_.attributes['org.gradle.category'] -eq 'library' })
        if ($variants.Count -ne 1) { throw "缺少唯一 $usage 变体：$Module" }
        if ($Module -ne 'log-android' -and $variants[0].attributes['org.gradle.jvm.version'] -ne 11) { throw 'Java module 未声明 JDK 11。' }
    }
    $sourceZip = [IO.Compression.ZipFile]::OpenRead($files.sources)
    try {
        Assert-License $sourceZip $Module
        if (@($sourceZip.Entries | Where-Object { $_.FullName.EndsWith('.java') }).Count -eq 0) { throw "源码 JAR 为空：$Module" }
    } finally { $sourceZip.Dispose() }
    $zip = [IO.Compression.ZipFile]::OpenRead($files.binary)
    try {
        if ($Module -eq 'log-android') {
            if ((Read-ZipEntryText $zip 'AndroidManifest.xml') -notmatch 'minSdkVersion="21"') { throw '远端 AAR minSdk 不是 21。' }
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
    $checks.Add([pscustomobject]@{ Check='RemotePublication'; Module=$Module; Classes=$classCount; BinarySha256=(Get-RequiredHash $files.binary); SourcesSha256=(Get-RequiredHash $files.sources) })
}

function New-Consumer([string]$Mode, [string]$Module) {
    # 每个消费者拥有空缓存和独立源码，不引用本仓库项目、mavenLocal 或任何文件仓库。
    $consumer = Assert-RunPath (Join-Path $runRoot "consumers/$Mode-$Module")
    foreach ($directory in @('gradle-home','user-home','m2','tmp','android-user','project-cache')) { [IO.Directory]::CreateDirectory((Join-Path $consumer $directory)) | Out-Null }
    $metadataSources = if ($Mode -eq 'ModuleOnly') { 'gradleMetadata()' } else { 'mavenPom(); ignoreGradleMetadataRedirection()' }
    $settings = @'
rootProject.name = '__NAME__'
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri('https://jitpack.io'); metadataSources { __METADATA__ } } }
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
    buildTypes { release { minifyEnabled true; proguardFiles getDefaultProguardFile('proguard-android-optimize.txt') } }
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
                    def kotlin = artifacts.findAll { it.moduleVersion.id.group == 'org.jetbrains.kotlin' && it.name.startsWith('kotlin-stdlib') }
                    assert !kotlin.empty
                    assert kotlin.every { it.moduleVersion.id.version == '1.8.0' } : kotlin
                }
            }
            artifacts.each { artifact ->
                def id = artifact.moduleVersion.id
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
        Write-RunText (Join-Path $consumer 'src/main/AndroidManifest.xml') '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:name=".ConsumerApplication" android:label="Remote verification" /></manifest>'
        Write-RunText (Join-Path $consumer 'src/main/java/com/atian10/logrecord/remoteverification/ConsumerApplication.java') @'
package com.atian10.logrecord.remoteverification;
import android.app.Application;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.android.AndroidLogInit;
/** 仅供编译和 R8 检查，验证入口绝不安装或运行此应用。 */
public final class ConsumerApplication extends Application {
    /** Manifest 入口保留实际 API 引用；此方法在验证期间不会执行。 */
    @Override public void onCreate() { super.onCreate(); AndroidLogInit.init(this, new LogConfig.Builder()); }
}
'@
    } else {
        $source = if ($Module -eq 'log-desktop') { 'public static void configure(String path) { com.atian10.logrecord.desktop.DesktopLogInit.init(path, new com.atian10.logrecord.core.config.LogConfig.Builder()); }' } else { 'public static com.atian10.logrecord.core.config.LogConfig.Builder configure() { return new com.atian10.logrecord.core.config.LogConfig.Builder(); }' }
        Write-RunText (Join-Path $consumer 'src/main/java/Consumer.java') ("/** 仅供编译检查，不运行初始化。 */`npublic final class Consumer { /** 核对公开 API 与传递依赖。 */ $source }")
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

function Assert-ConsumerOutputs([string]$ConsumerRoot, [string]$Mode, [string]$Module) {
    # 报告同时绑定编译/打包输出内容，不能只凭依赖图文件证明本轮消费完成。
    $outputPaths = [Collections.Generic.List[string]]::new()
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
    # 对每个实际使用的模块核对缓存中的解析元数据，证明两种路径分别使用对应文件。
    foreach ($resolvedModule in @($projectEntries.module | Sort-Object -Unique)) {
        $suffix = if ($Mode -eq 'ModuleOnly') { 'module' } else { 'pom' }
        $cache = Join-Path $ConsumerRoot "gradle-home/caches/modules-2/files-2.1/$group/$resolvedModule/$Version"
        $metadataFiles = @(Get-ChildItem -LiteralPath $cache -Recurse -File -Filter "$resolvedModule-$Version.$suffix")
        if ($metadataFiles.Count -ne 1 -or (Get-RequiredHash $metadataFiles[0].FullName) -ine (Get-RequiredHash $remoteArtifacts[$resolvedModule][$suffix])) { throw "实际解析的 $suffix 与远端原始元数据不同或缺失。" }
    }
    if ($Module -eq 'log-android') {
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
$inputPaths = @($PSCommandPath,(Join-Path $workspace 'jitpack.yml'),(Join-Path $workspace 'LICENSE'),
    (Join-Path $workspace 'gradle/wrapper/gradle-wrapper.jar'),(Join-Path $workspace 'gradle/wrapper/gradle-wrapper.properties'),
    (Join-Path $JavaHome 'release'),(Join-Path $JavaHome 'bin/java.exe'),(Join-Path $JavaHome 'bin/keytool.exe'),(Join-Path $SdkDirectory 'platforms/android-33/android.jar'))
$inputs = @($inputPaths | ForEach-Object { [pscustomobject]@{ Path=$_; Sha256=(Get-RequiredHash $_) } })
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch 'JAVA_VERSION="11\.') { throw '必须使用已有 JDK 11。' }
$wrapperProperties = Get-Content -LiteralPath (Join-Path $workspace 'gradle/wrapper/gradle-wrapper.properties') -Raw
if ($wrapperProperties -notmatch '(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-7\.5-all\.zip\s*$' -or $wrapperProperties -notmatch '(?m)^distributionSha256Sum=[a-fA-F0-9]{64}\s*$') { throw 'Wrapper 必须固定官方 Gradle 7.5 并具备分发校验值。' }
$licenseText = ((Get-Content -LiteralPath (Join-Path $workspace 'LICENSE') -Raw) -replace "`r`n","`n").Trim()
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $runRoot 'logs')) | Out-Null
$state = [ordered]@{ State='RUNNING'; Version=$Version; ExpectedCommit=$ExpectedCommit; StartedUtc=[DateTime]::UtcNow; Inputs=$inputs; Error=$null; RemoteCancellation='客户端取消或失败不代表服务端停止，也不撤销已发布制品。' }
Write-RunJson 'run.json' $state
$remoteLog = $null
try {
    foreach ($module in $modules) {
        $remoteArtifacts[$module] = @{}
        $extension = if ($module -eq 'log-android') { 'aar' } else { 'jar' }
        foreach ($kind in @('pom','module','binary','sources')) {
            $suffix = switch ($kind) { 'pom' { '.pom' }; 'module' { '.module' }; 'binary' { ".$extension" }; 'sources' { '-sources.jar' } }
            $fileName = "$module-$Version$suffix"
            $remoteArtifacts[$module][$kind] = Invoke-RemoteDownload "com/github/Atian10/log-record/$module/$Version/$fileName" "remote/$module/$fileName"
        }
        Assert-RemotePublication $module
    }
    # 构建日志来自同一版本，要求精确源码身份及真实工具输出；命令回显不能替代结果。
    $remoteLog = Invoke-RemoteDownload "com/github/Atian10/log-record/$Version/build.log" 'remote/build.log'
    $logText = (Get-Content -LiteralPath $remoteLog -Raw) -replace '\x1B\[[0-?]*[ -/]*[@-~]',''
    if ($logText -notmatch ('(?m)^JITPACK_PROVENANCE_COMMIT=' + [regex]::Escape($ExpectedCommit) + '\s*$') -or
        $logText -notmatch ('(?m)^JITPACK_PROVENANCE_VERSION=' + [regex]::Escape($Version) + '\s*$') -or
        $logText -notmatch '(?m)^Gradle 7\.5\s*$' -or $logText -notmatch '(?m)^JVM:\s+11[.\s]' -or $logText -notmatch 'BUILD SUCCESSFUL') { throw '远程日志未证明预期提交、版本和 Gradle 7.5/JVM 11 成功构建。' }
    foreach ($module in $modules) { if ($logText -notmatch ([regex]::Escape(":${module}:publishToMavenLocal"))) { throw "远程日志缺少 $module 发布任务。" } }
    foreach ($mode in @('ModuleOnly','PomOnly')) {
        foreach ($module in $modules) {
            $consumer = New-Consumer $mode $module
            Invoke-ConsumerGradle "$mode-$module-version" $consumer @('--version')
            if ($module -eq 'log-android') {
                # 仅生成本轮临时调试签名，不读取个人或正式签名。
                $keyArguments = @(('-J-Duser.home=' + (Join-Path $consumer 'user-home')),'-genkeypair','-noprompt','-keystore',(Join-Path $consumer 'android-user/debug.keystore'),'-storepass','android','-alias','androiddebugkey','-keypass','android','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-validity','30')
                Invoke-ConsumerProcess "$mode-$module-key" (Join-Path $JavaHome 'bin/keytool.exe') $keyArguments $consumer
                Invoke-ConsumerGradle "$mode-$module-build" $consumer @('assembleDebug','assembleRelease','verifyRuntimeGraph')
            } else { Invoke-ConsumerGradle "$mode-$module-build" $consumer @('classes','verifyRuntimeGraph') }
            Assert-ConsumerOutputs $consumer $mode $module
            Write-RunJson 'checks.json' @($checks.ToArray())
        }
    }
    foreach ($input in $inputs) { if ((Get-RequiredHash $input.Path) -cne $input.Sha256) { throw '验证期间脚本、工具或基准许可证发生变化。' } }
    if (@($checks | Where-Object { $_.Check -eq 'Consumer' }).Count -ne 6 -or @($checks | Where-Object { $_.Check -eq 'RemotePublication' }).Count -ne 3) { throw '验证矩阵不完整。' }
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
