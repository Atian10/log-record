#Requires -Version 7.0
<#
仅在固定的 JDK 11 / Gradle 7.5 / AGP 7.4.2 下执行已列非真机验证。
生成物和合成数据库均位于 build/verification；不安装、运行示例或访问设备。
#>
[CmdletBinding()]
param(
    # 本机已有的工具目录，不安装或自动下载 JDK、SDK。
    [string]$JavaHome = 'E:\Java\temurin-11',
    [string]$SdkDirectory = 'E:\AndroidDev\Sdk',
    # 离线模式使用已有 Wrapper 分发及只读依赖缓存；缺失即失败，不启动下载。
    [switch]$Offline,
    # 可显式选择受影响的验证阶段；每次调用生成独立日志和源码指纹。
    [ValidateSet('Environment','Tests','Android','Lint','Publish','Consumers','PublicationArtifacts','Artifacts')]
    [string[]]$Stages = @('Environment','Tests','Android','Lint','Publish','Consumers','Artifacts'),
    [ValidatePattern('^[a-zA-Z0-9-]+$')]
    [string]$RunId = ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 规范化仓库及产物根目录，拒绝重解析点将写入导向范围外。
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verificationBase = Join-Path $workspace 'build/verification'
$runRoot = Join-Path $verificationBase $RunId
$seedHome = Join-Path $workspace '.gradle/verification-home'
$gradleHome = if ($Offline) { Join-Path $runRoot 'gradle-home' } else { $seedHome }
$offlineLauncher = $null
$consumerModes = @('DefaultMaven','PomOnly')
$attemptId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6)
$attemptRoot = Join-Path $runRoot "attempts/$attemptId"
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$results = [Collections.Generic.List[object]]::new()

function Get-BuildInputState {
    # 哈希覆盖当前源码和构建入口；文档修改不使构建证据失效，生成目录由 Git 忽略规则排除。
    $inputPaths = @(& git -C $workspace -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw '无法枚举当前构建输入。' }
    $inputPaths += @('local.properties', 'scripts/check-verification-artifacts.ps1')
    $inputFiles = @($inputPaths | Where-Object { [IO.Path]::GetExtension($_) -ine '.md' } | Sort-Object -Unique | ForEach-Object {
        # 已删除的跟踪文件也属于输入变化，不能在指纹中静默消失。
        $inputPath = Join-Path $workspace $_
        [pscustomobject]@{ Path=$_; Hash=if (Test-Path -LiteralPath $inputPath -PathType Leaf) { (Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash } else { 'MISSING' } }
    })
    # 工具路径和实际 JDK/SDK 核心文件也进入身份，避免不同环境复用同一 receipt。
    $inputDescription = ConvertTo-Json -InputObject ([pscustomobject]@{
        Files=$inputFiles; JavaHome=$JavaHome; SdkDirectory=$SdkDirectory; Offline=[bool]$Offline; PublicationMetadata='Pom'
        OfflineLauncher=if ($Offline) { (Get-FileHash -LiteralPath $offlineLauncher).Hash } else { $null }
        JavaRelease=(Get-FileHash -LiteralPath (Join-Path $JavaHome 'release') -Algorithm SHA256).Hash
        AndroidJar=(Get-FileHash -LiteralPath (Join-Path $SdkDirectory 'platforms/android-33/android.jar') -Algorithm SHA256).Hash
    }) -Depth 6 -Compress
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $fingerprint = [BitConverter]::ToString($hasher.ComputeHash($utf8.GetBytes($inputDescription))).Replace('-', '') }
    finally { $hasher.Dispose() }
    return [pscustomobject]@{ Fingerprint=$fingerprint; Files=$inputFiles }
}

function Assert-CurrentBuildInputs {
    # 一次脚本调用中若源码或工具发生变化，必须新建尝试重新取证。
    if ((Get-BuildInputState).Fingerprint -cne $buildInputState.Fingerprint) { throw '验证期间构建输入发生变化，请重新启动验证尝试。' }
}

function Assert-WorkspacePath([string]$Path) {
    # 限定所有本轮写入位于仓库；逐级拒绝已有的符号链接或目录联接。
    $absolutePath = [IO.Path]::GetFullPath($Path)
    if (!$absolutePath.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "写入路径越界：$absolutePath"
    }
    $ancestor = $absolutePath
    while ($ancestor -and $ancestor -ne $workspace) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "验证路径含重解析点：$ancestor"
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $absolutePath
}

function Write-VerificationText([string]$Path, [string]$Content) {
    # 统一生成文件为 UTF-8，调用前验证目标目录范围。
    $absolutePath = Assert-WorkspacePath $Path
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($absolutePath)) | Out-Null
    [IO.File]::WriteAllText($absolutePath, $Content, $utf8)
}

function Assert-RunPath([string]$Path) {
    # receipt、报告和制品只能引用本 RunId 的目录，禁止借路径读取或清理其他位置。
    $absolutePath = Assert-WorkspacePath $Path
    if (!$absolutePath.StartsWith($runRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "证据路径不属于当前运行：$absolutePath"
    }
    return $absolutePath
}

function Get-OutputHashes([string[]]$Paths) {
    # 每个指定输出都必须存在；目录递归枚举文件，拒绝空输出集合和重解析点。
    $outputFiles = @(
        foreach ($outputPath in $Paths) {
            $absolutePath = Assert-RunPath $outputPath
            if (!(Test-Path -LiteralPath $absolutePath)) { throw "缺少阶段输出：$absolutePath" }
            Get-ChildItem -LiteralPath $absolutePath -Recurse -File
        }
    )
    if ($outputFiles.Count -eq 0) { throw '阶段没有可核验输出。' }
    return @($outputFiles | Sort-Object FullName -Unique | ForEach-Object {
        # 文件逐一核验路径和内容，收据保存绝对位置、长度及 SHA-256。
        $outputFile = Assert-RunPath $_.FullName
        [pscustomobject]@{ Path=$outputFile; Bytes=$_.Length; Sha256=(Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash }
    })
}

function Assert-StageReceipt([string]$Stage) {
    # 只有成功、输入一致且全部输出未变的阶段才能被后续步骤使用。
    Assert-CurrentBuildInputs
    $receiptPath = Assert-RunPath (Join-Path $runRoot "receipts/$Stage.json")
    if (!(Test-Path -LiteralPath $receiptPath -PathType Leaf)) { throw "缺少当前 $Stage 阶段的成功记录。" }
    $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
    if ($receipt.State -ne 'PASSED' -or $receipt.Fingerprint -cne $buildInputState.Fingerprint) {
        throw "$Stage 阶段未成功，或其构建输入已过期。"
    }
    if (@($receipt.Outputs).Count -eq 0) { throw "$Stage 阶段记录没有输出证据。" }
    foreach ($output in $receipt.Outputs) {
        # 只检查记录中的文件；新增无关输出不替代已记录的内容。
        $outputPath = Assert-RunPath $output.Path
        if (!(Test-Path -LiteralPath $outputPath -PathType Leaf) -or
            (Get-Item -LiteralPath $outputPath).Length -ne $output.Bytes -or
            (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash -cne $output.Sha256) {
            throw "$Stage 阶段输出已缺失或变化：$outputPath"
        }
    }
    foreach ($dependency in $receipt.Dependencies) {
        # 发布重跑即使内容相同，也要求消费验证明确承接新的发布记录。
        $dependencyHash = Assert-StageReceipt $dependency.Stage
        if ($dependencyHash -cne $dependency.ReceiptHash) { throw "$Stage 所依赖的 $($dependency.Stage) 记录已经变化。" }
    }
    return (Get-FileHash -LiteralPath $receiptPath -Algorithm SHA256).Hash
}

function Start-VerificationStage([string]$Stage, [string[]]$Dependencies = @()) {
    # 先使旧成功记录失效；即使前置条件或执行失败，旧结果也不能继续被使用。
    $receipt = [pscustomobject]@{ Stage=$Stage; State='RUNNING'; Attempt=$attemptId; StartedUtc=[DateTime]::UtcNow; Fingerprint=$buildInputState.Fingerprint; Dependencies=@(); Outputs=@() }
    Write-VerificationText (Join-Path $runRoot "receipts/$Stage.json") (ConvertTo-Json -InputObject $receipt -Depth 8)
    Assert-CurrentBuildInputs
    $receipt.Dependencies = @($Dependencies | ForEach-Object { [pscustomobject]@{ Stage=$_; ReceiptHash=(Assert-StageReceipt $_) } })
    return $receipt
}

function Complete-VerificationStage([object]$Receipt, [string[]]$OutputPaths) {
    # 保留本次执行的输入与依赖身份；校验通过后才写入成功状态。
    Assert-CurrentBuildInputs
    foreach ($dependency in $Receipt.Dependencies) {
        if ((Assert-StageReceipt $dependency.Stage) -cne $dependency.ReceiptHash) { throw "阶段执行期间依赖记录变化：$($dependency.Stage)" }
    }
    $Receipt.Outputs = @(Get-OutputHashes $OutputPaths)
    $Receipt.State = 'PASSED'
    Write-VerificationText (Join-Path $runRoot "receipts/$($Receipt.Stage).json") (ConvertTo-Json -InputObject $Receipt -Depth 8)
    Write-VerificationText (Join-Path $attemptRoot "$($Receipt.Stage).receipt.json") (ConvertTo-Json -InputObject $Receipt -Depth 8)
}

function Save-PreviousTestReports {
    # 重跑先归档旧 XML/HTML，再清理准确的生成目录，防止旧报告混入当前执行。
    foreach ($testModule in @('log-core','log-desktop')) {
        foreach ($reportKind in @('test-results/test','reports/tests/test')) {
            $reportPath = Assert-RunPath (Join-Path $runRoot "modules/$testModule/$reportKind")
            if (Test-Path -LiteralPath $reportPath -PathType Container) {
                $historyPath = Assert-RunPath (Join-Path $attemptRoot "history/$testModule/$reportKind")
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($historyPath)) | Out-Null
                Copy-Item -LiteralPath $reportPath -Destination $historyPath -Recurse
                # 删除目标已规范化且限制于本轮目录，归档成功后才清除原报告。
                Remove-Item -LiteralPath $reportPath -Recurse -Force
            }
        }
    }
}

function Invoke-VerificationProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory, [string]$ProcessGradleHome = $gradleHome) {
    # 使用参数数组直接启动进程，不经 shell 拼接；只终止本次进程树。
    $stdoutPath = Join-Path $attemptRoot "$Name.stdout.log"
    $stderrPath = Join-Path $attemptRoot "$Name.stderr.log"
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Executable
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
    foreach ($environmentName in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS','GRADLE_RO_DEP_CACHE')) {
        [void]$startInfo.Environment.Remove($environmentName)
    }
    $startInfo.Environment['JAVA_HOME'] = $JavaHome
    $startInfo.Environment['ANDROID_HOME'] = $SdkDirectory
    $startInfo.Environment['ANDROID_SDK_ROOT'] = $SdkDirectory
    $startInfo.Environment['ANDROID_USER_HOME'] = Join-Path $runRoot 'android-user'
    $startInfo.Environment['GRADLE_USER_HOME'] = $ProcessGradleHome
    if ($Offline) { $startInfo.Environment['GRADLE_RO_DEP_CACHE'] = Join-Path $seedHome 'caches' }
    $startInfo.Environment['HOME'] = Join-Path $runRoot 'user-home'
    $startInfo.Environment['USERPROFILE'] = Join-Path $runRoot 'user-home'
    $startInfo.Environment['TEMP'] = Join-Path $runRoot 'tmp/gradle'
    $startInfo.Environment['TMP'] = Join-Path $runRoot 'tmp/gradle'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    # 预置完整状态，启动、日志或回收失败也能写入本次命令记录。
    $stdoutFile = $null
    $stderrFile = $null
    $stdoutCopy = $null
    $stderrCopy = $null
    $exitCode = $null
    $failure = $null
    $cleanupFailures = [Collections.Generic.List[string]]::new()
    $startedAt = [DateTime]::UtcNow
    $timedOut = $false
    $started = $false
    # 单调时钟覆盖进程与日志排空；取消令牌用于异常退出时停止管道复制。
    $deadlineMilliseconds = 20 * 60 * 1000
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    $copyCancellation = [Threading.CancellationTokenSource]::new()
    try {
        $stdoutFile = [IO.File]::Create($stdoutPath)
        $stderrFile = [IO.File]::Create($stderrPath)
        Write-Output "START $Name"
        if (!$process.Start()) { throw "进程未启动：$Name" }
        $started = $true
        $stdoutCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdoutFile, 81920, $copyCancellation.Token)
        $stderrCopy = $process.StandardError.BaseStream.CopyToAsync($stderrFile, 81920, $copyCancellation.Token)
        while (!$process.WaitForExit(1000)) {
            if ($elapsed.ElapsedMilliseconds -ge $deadlineMilliseconds) {
                $timedOut = $true
                throw [TimeoutException]::new("进程超过二十分钟截止：$Name")
            }
        }
        $exitCode = $process.ExitCode
        # 进程退出后排空最多十秒，且不能越过整条命令的二十分钟截止。
        $drainMilliseconds = [int][Math]::Max(0, [Math]::Min(10000, $deadlineMilliseconds - $elapsed.ElapsedMilliseconds))
        $copies = [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($stdoutCopy, $stderrCopy))
        if ($drainMilliseconds -eq 0 -or !$copies.Wait($drainMilliseconds)) {
            $timedOut = $true
            throw [TimeoutException]::new("输出管道未在截止内排空：$Name")
        }
        if ($exitCode -ne 0) { throw "验证进程失败：$Name，退出码 $exitCode" }
    } catch {
        # 首错用于最终抛出，后续回收错误单独保存，不能覆盖首错。
        $failure = $_.Exception
    } finally {
        try {
            if ($started -and !$process.HasExited) {
                $process.Kill($true)
                if (!$process.WaitForExit(10000)) { throw "本次进程树未能在截止后结束：$Name" }
            }
            if ($started -and $process.HasExited) { $exitCode = $process.ExitCode }
        } catch { $cleanupFailures.Add($_.Exception.ToString()) }
        # 所有复制任务均有界等待；不调用会输出 VoidTaskResult 或无限等待的 GetResult。
        try { $copyCancellation.Cancel() } catch { $cleanupFailures.Add($_.Exception.ToString()) }
        if ($started) {
            try { $process.StandardOutput.Dispose(); $process.StandardError.Dispose() }
            catch { $cleanupFailures.Add($_.Exception.ToString()) }
        }
        foreach ($copyTask in @($stdoutCopy, $stderrCopy)) {
            if ($null -ne $copyTask) {
                try {
                    if (!$copyTask.Wait(1000)) { $cleanupFailures.Add('管道复制取消后仍未结束。') }
                } catch {
                    # 失败路径中主动取消造成的异常已由首错解释；正常路径出现复制错误仍需报告。
                    if ($null -eq $failure) { $cleanupFailures.Add($_.Exception.ToString()) }
                }
            }
        }
        foreach ($resource in @($stdoutFile, $stderrFile, $copyCancellation, $process)) {
            if ($null -ne $resource) {
                try { $resource.Dispose() } catch { $cleanupFailures.Add($_.Exception.ToString()) }
            }
        }
        $elapsed.Stop()
        if ($null -eq $failure -and $cleanupFailures.Count -gt 0) { $failure = [InvalidOperationException]::new($cleanupFailures[0]) }
        # 命令失败也持久化状态；记录写入失败本身同样必须使调用失败。
        $results.Add([pscustomobject]@{
            Name=$Name; StartedUtc=$startedAt; Started=$started; ExitCode=$exitCode; TimedOut=$timedOut
            ElapsedMilliseconds=$elapsed.ElapsedMilliseconds; Arguments=$Arguments; Stdout=$stdoutPath; Stderr=$stderrPath
            Offline=[bool]$Offline; GradleHome=$ProcessGradleHome; ReadOnlyDependencyCache=if ($Offline) { Join-Path $seedHome 'caches' } else { $null }
            Error=if ($null -ne $failure) { $failure.ToString() } else { $null }; CleanupErrors=@($cleanupFailures.ToArray())
        })
        try { Write-VerificationText (Join-Path $attemptRoot 'commands.json') (ConvertTo-Json -InputObject @($results.ToArray()) -Depth 6) }
        catch {
            [Console]::Error.WriteLine("写入命令记录失败：$($_.Exception)")
            if ($null -eq $failure) { $failure = $_.Exception }
        }
    }
    if ($null -ne $failure) {
        foreach ($logPath in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $logPath -PathType Leaf) {
                try { Get-Content -LiteralPath $logPath -Tail 35 | Write-Output } catch { [Console]::Error.WriteLine($_.Exception.Message) }
            }
        }
        throw $failure
    }
    Write-Output "PASS $Name"
}

function Invoke-GradleVerification([string]$Name, [string[]]$Tasks, [string]$ProjectDirectory = $workspace) {
    # --offline 不限制 Wrapper 下载；离线时只启动已验证的 Wrapper 缓存分发。
    $projectName = Split-Path -Leaf $ProjectDirectory
    $processHome = if ($Offline -and $ProjectDirectory -ne $workspace) { Join-Path $runRoot "consumer-caches/$projectName" } else { $gradleHome }
    [IO.Directory]::CreateDirectory((Assert-WorkspacePath $processHome)) | Out-Null
    $launcher = if ($Offline) { $offlineLauncher } else { Join-Path $workspace 'gradle/wrapper/gradle-wrapper.jar' }
    $mainClass = if ($Offline) { 'org.gradle.launcher.GradleMain' } else { 'org.gradle.wrapper.GradleWrapperMain' }
    $arguments = @('-Dfile.encoding=UTF-8', ('-Djava.io.tmpdir=' + (Join-Path $runRoot 'tmp/gradle')), ('-Duser.home=' + (Join-Path $runRoot 'user-home')),
        '-classpath', $launcher, $mainClass,
        '--no-daemon','--no-parallel','--no-build-cache','--no-configuration-cache','--console=plain','--stacktrace',
        '-g', $processHome, '-p', $ProjectDirectory, '--project-cache-dir', (Join-Path $runRoot "project-cache/$projectName"),
        '-I', (Join-Path $workspace 'gradle/verification.init.gradle'),
        '-I', (Join-Path $runRoot 'scope.init.gradle'),
        ('-Dorg.gradle.java.home=' + $JavaHome), ('-Dmaven.repo.local=' + (Join-Path $runRoot 'm2')),
        ('-PverificationWorkspace=' + $workspace), ('-PverificationRoot=' + $runRoot),
        ('-PverificationJavaHome=' + $JavaHome), ('-ProomSchemaDir=' + (Join-Path $runRoot 'schemas')),
        '-PlibraryVersion=0.0.0-local-validation','-Pandroid.builder.sdkDownload=false','-Porg.gradle.java.installations.auto-download=false')
    if ($Offline) { $arguments += '--offline' }
    $arguments += $Tasks
    Invoke-VerificationProcess $Name (Join-Path $JavaHome 'bin/java.exe') $arguments $ProjectDirectory $processHome
}

function New-ConsumerProjects([string]$Mode) {
    # 每个模式三个独立项目；DefaultMaven 不配置 metadataSources，不能与 PomOnly 同义。
    $repositoryUri = ([uri](Join-Path $runRoot 'm2')).AbsoluteUri
    $prefix = if ($Mode -eq 'DefaultMaven') { 'consumer' } else { 'consumer-pom' }
    $metadataBlock = if ($Mode -eq 'PomOnly') { 'metadataSources { mavenPom(); ignoreGradleMetadataRedirection() }' } else { '' }
    $repositories = "repositories { google(); mavenCentral(); exclusiveContent { forRepository { maven { url '$repositoryUri'; $metadataBlock } }; filter { includeGroup 'com.github.Atian10.log-record' } } }"
    $javaConsumer = Join-Path $runRoot "consumers/$prefix-java"
    $androidConsumer = Join-Path $runRoot "consumers/$prefix-android"
    # 直接 core 消费方可以发现仅由平台适配层掩盖的公开依赖缺失。
    $coreConsumer = Join-Path $runRoot "consumers/$prefix-core"
    Write-VerificationText (Join-Path $coreConsumer 'settings.gradle') "rootProject.name = '$prefix-core'"
    Write-VerificationText (Join-Path $coreConsumer 'build.gradle') @"
plugins { id 'java' }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
tasks.withType(JavaCompile).configureEach { options.release = 11 }
$repositories
dependencies { implementation 'com.github.Atian10.log-record:log-core:0.0.0-local-validation' }
// 断言原始发布制品来自本轮 m2，不运行 Consumer 中的方法。
tasks.register('verifyRuntimeGraph') {
    doLast {
        // 直接核心模块仅应解析自身和 Gson；禁止平台依赖倒流。
        def artifacts = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts
        def names = artifacts.collect { it.name }.sort()
        assert names == ['gson', 'log-core'] : names
        artifacts.findAll { it.name.startsWith('log-') }.each {
            assert it.moduleVersion.id.group == 'com.github.Atian10.log-record'
            assert it.moduleVersion.id.version == '0.0.0-local-validation'
            assert it.file.canonicalFile.toPath().startsWith(new File(new URI('$repositoryUri')).canonicalFile.toPath()) : it.file
        }
        println names
    }
}
"@
    Write-VerificationText (Join-Path $coreConsumer 'src/main/java/Consumer.java') @'
import com.atian10.logrecord.core.config.LogConfig;
import com.google.gson.Gson;
/** 仅编译核心 API 及传递 Gson API，不运行。 */
public final class Consumer {
    /** 合成入口验证类型可用，不产生日志或数据库。 */
    public static LogConfig.Builder configuration(Gson gson) { return new LogConfig.Builder(); }
}
'@
    Write-VerificationText (Join-Path $javaConsumer 'settings.gradle') "rootProject.name = '$prefix-java'"
    Write-VerificationText (Join-Path $javaConsumer 'build.gradle') @"
plugins { id 'java' }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
tasks.withType(JavaCompile).configureEach { options.release = 11 }
$repositories
dependencies { implementation 'com.github.Atian10.log-record:log-desktop:0.0.0-local-validation' }
tasks.register('verifyRuntimeGraph') {
    doLast {
        def artifacts = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts
        def names = artifacts.collect { it.name }
        assert names.containsAll(['log-desktop', 'log-core', 'gson', 'sqlite-jdbc']) : names
        assert !names.contains('log-android') : names
        artifacts.findAll { it.name.startsWith('log-') }.each {
            assert it.moduleVersion.id.group == 'com.github.Atian10.log-record'
            assert it.moduleVersion.id.version == '0.0.0-local-validation'
            assert it.file.canonicalFile.toPath().startsWith(new File(new URI('$repositoryUri')).canonicalFile.toPath()) : it.file
        }
        println names.sort()
    }
}
"@
    Write-VerificationText (Join-Path $javaConsumer 'src/main/java/Consumer.java') @'
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.desktop.DesktopLogInit;
/** 仅供编译检查的合成消费方，不运行初始化。 */
public final class Consumer {
    /** 验证发布元数据可以传递公开 API 依赖。 */
    public static void initialize(String path) { DesktopLogInit.init(path, new LogConfig.Builder()); }
}
'@
    Write-VerificationText (Join-Path $androidConsumer 'settings.gradle') "rootProject.name = '$prefix-android'"
    Write-VerificationText (Join-Path $androidConsumer 'build.gradle') @"
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath 'com.android.tools.build:gradle:7.4.2' }
}
apply plugin: 'com.android.application'
android {
    namespace 'com.atian10.logrecord.verification'
    compileSdk 33
    defaultConfig { applicationId 'com.atian10.logrecord.verification'; minSdk 21; targetSdk 33; versionCode 1; versionName 'local' }
    compileOptions { sourceCompatibility JavaVersion.VERSION_11; targetCompatibility JavaVersion.VERSION_11 }
    // 消费方 Release 只做未签名打包及 R8，不安装或运行。
    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
        }
    }
}
$repositories
dependencies { implementation 'com.github.Atian10.log-record:log-android:0.0.0-local-validation' }
tasks.register('verifyRuntimeGraph') {
    doLast {
        // 两个消费变体均需取得相同的平台依赖和 Kotlin 对齐结果。
        ['debugRuntimeClasspath', 'releaseRuntimeClasspath'].each { configurationName ->
            def artifacts = configurations.getByName(configurationName).resolvedConfiguration.resolvedArtifacts
            def names = artifacts.collect { it.name }
            assert names.containsAll(['log-android', 'log-core', 'gson', 'room-runtime']) : names
            assert !names.contains('log-desktop') && !names.contains('sqlite-jdbc') : names
            artifacts.findAll { it.moduleVersion.id.group == 'org.jetbrains.kotlin' && it.name.startsWith('kotlin-stdlib') }.each {
                assert it.moduleVersion.id.version == '1.8.0' : it.moduleVersion.id
            }
            artifacts.findAll { it.name.startsWith('log-') }.each {
                assert it.moduleVersion.id.group == 'com.github.Atian10.log-record'
                assert it.moduleVersion.id.version == '0.0.0-local-validation'
                assert it.file.canonicalFile.toPath().startsWith(new File(new URI('$repositoryUri')).canonicalFile.toPath()) : it.file
            }
            println configurationName + ': ' + names.sort()
        }
    }
}
"@
    Write-VerificationText (Join-Path $androidConsumer 'gradle.properties') "android.useAndroidX=true"
    Write-VerificationText (Join-Path $androidConsumer 'src/main/AndroidManifest.xml') '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="Local verification" /></manifest>'
    Write-VerificationText (Join-Path $androidConsumer 'src/main/java/Consumer.java') @'
import android.content.Context;
import com.atian10.logrecord.core.config.LogConfig;
import com.atian10.logrecord.android.AndroidLogInit;
/** 仅供编译检查的合成消费方，不安装或调用初始化。 */
public final class Consumer {
    /** 验证 AAR 及其 core 传递依赖的公开 API。 */
    public static void initialize(Context context) { AndroidLogInit.init(context, new LogConfig.Builder()); }
}
'@
    # 本地 Maven 文件来源与原始下载来源分开记账；本轮不访问远端仓库。
    foreach ($entry in @(@($coreConsumer,'log-core'),@($javaConsumer,'log-desktop'),@($androidConsumer,'log-android'))) {
        $consumerPath = $entry[0]; $module = $entry[1]
        $graphChecks = @'

configurations {
    verificationSources { transitive = false; canBeConsumed = false }
    verificationPoms { transitive = false; canBeConsumed = false }
    verificationBom { transitive = false; canBeConsumed = false }
}
def libraryModules = '__MODULE__' == 'log-core' ? ['log-core'] : ['__MODULE__', 'log-core']
dependencies {
    libraryModules.each { name ->
        verificationSources "com.github.Atian10.log-record:${name}:0.0.0-local-validation:sources@jar"
        verificationPoms "com.github.Atian10.log-record:${name}:0.0.0-local-validation@pom"
    }
    if ('__MODULE__' == 'log-android') verificationBom 'org.jetbrains.kotlin:kotlin-bom:1.8.0@pom'
}
tasks.named('verifyRuntimeGraph') {
    doLast {
        assert gradle.gradleVersion == '7.5' && JavaVersion.current() == JavaVersion.VERSION_11
        def names = '__MODULE__' == 'log-android' ? ['debugRuntimeClasspath','releaseRuntimeClasspath'] : ['runtimeClasspath']
        names += ['verificationSources','verificationPoms']
        if ('__MODULE__' == 'log-android') names += 'verificationBom'
        def reports = []
        names.each { configurationName ->
            def configuration = configurations.getByName(configurationName)
            assert configuration.incoming.resolutionResult.allComponents.every {
                !(it.id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier) || it.id == configuration.incoming.resolutionResult.root.id
            } : 'Project dependency substitution is forbidden'
            def artifacts = configuration.resolvedConfiguration.resolvedArtifacts
            assert !artifacts.empty : configurationName
            if (configurationName.endsWith('RuntimeClasspath') || configurationName == 'runtimeClasspath') {
                def expected = ['com.google.code.gson:gson':'2.10.1']
                if ('__MODULE__' == 'log-desktop') expected['org.xerial:sqlite-jdbc'] = '3.42.0.0'
                if ('__MODULE__' == 'log-android') expected['androidx.room:room-runtime'] = '2.5.2'
                expected.each { id, version ->
                    def matches = artifacts.findAll { "${it.moduleVersion.id.group}:${it.name}" == id }
                    assert matches.size() == 1 && matches[0].moduleVersion.id.version == version : "${id}: ${matches}"
                }
                if ('__MODULE__' == 'log-android') {
                    def annotations = artifacts.findAll { it.moduleVersion.id.group == 'androidx.annotation' && (it.name in ['annotation','annotation-jvm']) }
                    assert !annotations.empty && annotations.every { it.moduleVersion.id.version == '1.6.0' }
                    def kotlin = artifacts.findAll { it.moduleVersion.id.group == 'org.jetbrains.kotlin' && it.name.startsWith('kotlin-stdlib') }
                    assert !kotlin.empty && kotlin.every { it.moduleVersion.id.version == '1.8.0' }
                }
            }
            artifacts.each { artifact ->
                def id = artifact.moduleVersion.id
                if (id.group == 'com.github.Atian10.log-record') {
                    assert id.name in libraryModules && id.version == '0.0.0-local-validation'
                    assert artifact.file.canonicalFile.toPath().startsWith(new File(new URI('__REPOSITORY__')).canonicalFile.toPath())
                }
                if (configurationName == 'verificationBom') {
                    assert id.group == 'org.jetbrains.kotlin' && id.name == 'kotlin-bom' && id.version == '1.8.0' && artifact.extension == 'pom'
                }
                reports << [configuration:configurationName, group:id.group, module:id.name, version:id.version,
                    classifier:artifact.classifier, extension:artifact.extension, path:artifact.file.canonicalPath,
                    sha256:java.security.MessageDigest.getInstance('SHA-256').digest(artifact.file.bytes).encodeHex().toString()]
            }
        }
        file('resolved-artifacts.json').text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(
            [mode:'__MODE__', module:'__MODULE__', publicationMetadata:'Pom', offline:gradle.startParameter.offline,
             libraryRepository:'__REPOSITORY__', gradleHome:gradle.gradleUserHomeDir.canonicalPath, artifacts:reports]))
    }
}
'@
        $buildFile = Join-Path $consumerPath 'build.gradle'
        $graphChecks = $graphChecks.Replace('__MODULE__',$module).Replace('__MODE__',$Mode).Replace('__REPOSITORY__',$repositoryUri)
        Write-VerificationText $buildFile ((Get-Content -LiteralPath $buildFile -Raw) + $graphChecks)
    }
}

function Assert-OfflineDistribution {
    # 必须在启动 Java/Gradle 之前完成，避免 --offline 到达 Gradle 之前 Wrapper 已联网。
    $wrapper = Get-Content -LiteralPath (Join-Path $workspace 'gradle/wrapper/gradle-wrapper.properties') -Raw
    if ($wrapper -notmatch '(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-7\.5-all\.zip\s*$' -or
        $wrapper -notmatch '(?m)^distributionSha256Sum=97a52d145762adc241bad7fd18289bf7f6801e08ece6badf80402fe2b9f250b1\s*$') { throw '离线验证要求原官方 Gradle 7.5 Wrapper 和固定校验值。' }
    $distributionRoot = Join-Path $seedHome 'wrapper/dists/gradle-7.5-all'
    if (!(Test-Path -LiteralPath $distributionRoot)) { throw '缺少已有 Gradle 7.5 分发；离线模式不会下载。' }
    $launchers = @(Get-ChildItem -LiteralPath $distributionRoot -Directory | ForEach-Object {
        $launcher = Join-Path $_.FullName 'gradle-7.5/lib/gradle-launcher-7.5.jar'
        if ((Test-Path -LiteralPath (Join-Path $_.FullName 'gradle-7.5-all.zip.ok')) -and (Test-Path -LiteralPath $launcher)) { $launcher }
    })
    if ($launchers.Count -ne 1) { throw '未发现唯一且已完成 Wrapper 校验的 Gradle 7.5 分发；不会自动下载。' }
    if (!(Test-Path -LiteralPath (Join-Path $seedHome 'caches/modules-2'))) { throw '缺少已有离线依赖缓存。' }
    $script:offlineLauncher = Assert-WorkspacePath $launchers[0]
}

function Assert-LocalSdkDirectory {
    # local.properties 优先于环境变量；只读核对 AS 配置，不覆盖用户已有文件。
    $localProperties = Join-Path $workspace 'local.properties'
    if (!(Test-Path -LiteralPath $localProperties -PathType Leaf)) { return }
    $sdkEntries = @(Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*[:=]' })
    if ($sdkEntries.Count -eq 0) { return }
    if ($sdkEntries.Count -ne 1) { throw 'local.properties 存在多个 sdk.dir，请先明确实际 SDK 配置。' }
    # 解码 Java properties 的转义，不把 E\:\\... 当作字面目录。
    $encodedSdk = ($sdkEntries[0] -replace '^\s*sdk\.dir\s*[:=]\s*', '').TrimEnd()
    if (($encodedSdk.Length - $encodedSdk.TrimEnd('\').Length) % 2 -ne 0) { throw '暂不支持跨行 sdk.dir，请使用 AS 生成的单行目录配置。' }
    $decodedSdk = [regex]::Replace($encodedSdk, '\\u([0-9a-fA-F]{4})|\\(.)', {
        param($escapeMatch)
        if ($escapeMatch.Groups[1].Success) { return [string][char][Convert]::ToInt32($escapeMatch.Groups[1].Value, 16) }
        switch -CaseSensitive ($escapeMatch.Groups[2].Value) {
            't' { return "`t" }; 'r' { return "`r" }; 'n' { return "`n" }; 'f' { return [string][char]12 }
            default { return $escapeMatch.Groups[2].Value }
        }
    })
    # 相对值按当前工程解析；规范化后再与命令参数比较。
    $configuredSdk = if ([IO.Path]::IsPathRooted($decodedSdk)) { [IO.Path]::GetFullPath($decodedSdk) } else { [IO.Path]::GetFullPath((Join-Path $workspace $decodedSdk)) }
    if (!$configuredSdk.TrimEnd('\','/').Equals($SdkDirectory.TrimEnd('\','/'), [StringComparison]::OrdinalIgnoreCase)) {
        throw "SDK 配置不一致：local.properties=$configuredSdk；验证参数=$SdkDirectory"
    }
}

function Initialize-VerificationDebugKey {
    # 只在所属阶段旧记录失效后准备一次性签名，失败时不能继续使用旧阶段成功状态。
    if (!(Test-Path -LiteralPath (Join-Path $runRoot 'keys/debug.keystore'))) {
        Invoke-VerificationProcess 'debug-key' (Join-Path $JavaHome 'bin/keytool.exe') @('-genkeypair','-keystore',(Join-Path $runRoot 'keys/debug.keystore'),'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','30','-dname','CN=Local Verification,O=LogRecord,C=CN','-noprompt') $workspace
    }
}

# 工具参数仅规范化本次进程使用的路径，不修改全局环境配置。
$JavaHome = [IO.Path]::GetFullPath($JavaHome)
$SdkDirectory = [IO.Path]::GetFullPath($SdkDirectory)
Assert-LocalSdkDirectory
if ($Offline) { Assert-OfflineDistribution }
foreach ($path in @($runRoot, $attemptRoot, $gradleHome, (Join-Path $runRoot 'tmp/gradle'), (Join-Path $runRoot 'keys'), (Join-Path $runRoot 'android-user'), (Join-Path $runRoot 'user-home'))) {
    [IO.Directory]::CreateDirectory((Assert-WorkspacePath $path)) | Out-Null
}
foreach ($requiredFile in @((Join-Path $JavaHome 'bin/java.exe'), (Join-Path $JavaHome 'bin/keytool.exe'), (Join-Path $SdkDirectory 'platforms/android-33/android.jar'), (Join-Path $SdkDirectory 'build-tools/30.0.3/aapt2.exe'))) {
    if (!(Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "缺少已约定工具组件：$requiredFile" }
}
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch 'JAVA_VERSION="11\.') { throw '验证必须使用已安装的 JDK 11。' }
$scopeGuard = @'
// 按当次选择阶段限制任务图；不修改工程公共 init 脚本。
gradle.taskGraph.whenReady { graph ->
    if (__OFFLINE__ && !gradle.startParameter.offline) throw new GradleException('离线模式必须传递 --offline')
    graph.allTasks.each { task ->
        if (task instanceof org.gradle.api.tasks.JavaExec ||
            (!__TESTS__ && (task instanceof org.gradle.api.tasks.testing.Test || task.name ==~ /(?i).*test.*/)) ||
            (!__LINT__ && task.name ==~ /(?i)lint(?!Vital).*/) ||
            task.name ==~ /(?i)(connected.*|managedDevice.*|install.*|uninstall.*|run.*|.*To.*Repository)/) {
            throw new GradleException("当前阶段不允许执行任务：${task.path}")
        }
    }
}
'@
Write-VerificationText (Join-Path $runRoot 'scope.init.gradle') ($scopeGuard.Replace('__OFFLINE__',([bool]$Offline).ToString().ToLowerInvariant()).Replace('__TESTS__',($Stages -contains 'Tests').ToString().ToLowerInvariant()).Replace('__LINT__',($Stages -contains 'Lint').ToString().ToLowerInvariant()))
# 阶段记录共用本次稳定的构建输入指纹；后续每阶段再次核验，避免运行中途换源码。
$buildInputState = Get-BuildInputState
# 本轮源码指纹包括未跟踪的新脚本；不把旧 HEAD 单独当成实际验证源码身份。
$sourcePaths = @(& git -C $workspace -c core.quotepath=false ls-files) + @('jitpack.yml','gradle/verification.init.gradle','scripts/verify-nondevice.ps1','scripts/check-verification-artifacts.ps1')
$sourceHashes = @($sourcePaths | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath (Join-Path $workspace $_) -PathType Leaf } | ForEach-Object {
    [pscustomobject]@{ Path=$_; Hash=(Get-FileHash -LiteralPath (Join-Path $workspace $_) -Algorithm SHA256).Hash }
})
Write-VerificationText (Join-Path $attemptRoot 'source-state.json') (ConvertTo-Json -InputObject ([pscustomobject]@{ Head=(& git -C $workspace rev-parse HEAD); Files=$sourceHashes; BuildFingerprint=$buildInputState.Fingerprint; BuildInputs=$buildInputState.Files; JavaHome=$JavaHome; Sdk=$SdkDirectory; Stages=$Stages; Offline=[bool]$Offline; PublicationMetadata='Pom'; ConsumerModes=$consumerModes; OfflineLauncher=$offlineLauncher; ReadOnlyDependencyCache=if ($Offline) { Join-Path $seedHome 'caches' } else { $null } }) -Depth 6)
Write-Output "RUN_ROOT $runRoot"

try {
if ('Environment' -in $Stages) {
    # Environment 仅证明本轮实际工具与任务清单，输出固定在当前尝试目录。
    $environmentReceipt = Start-VerificationStage 'Environment'
    Invoke-GradleVerification 'version' @('--version')
    Invoke-GradleVerification 'tasks' @('projects', ':log-core:tasks', ':log-desktop:tasks', ':log-android:tasks', ':log-sample-android:tasks', '--all')
    Complete-VerificationStage $environmentReceipt @((Join-Path $attemptRoot 'version.stdout.log'), (Join-Path $attemptRoot 'tasks.stdout.log'))
}
if ('Tests' -in $Stages) {
    # 先使旧成功状态失效并保留旧报告，再强制执行本轮测试任务。
    $testsReceipt = Start-VerificationStage 'Tests'
    Save-PreviousTestReports
    Invoke-GradleVerification 'jvm-tests' @(':log-core:test', ':log-desktop:test', ':log-sample:classes', '--rerun-tasks')
    # 任务成功之外还检查实际 JUnit 报告，空报告和跳过不能冒充测试执行。
    $testSummaries = @()
    foreach ($testModule in @('log-core','log-desktop')) {
        $testReports = @(Get-ChildItem -LiteralPath (Join-Path $runRoot "modules/$testModule/test-results/test") -Filter 'TEST-*.xml' -File)
        $testCount = 0; $failureCount = 0; $skippedCount = 0
        foreach ($testReport in $testReports) {
            [xml]$testXml = Get-Content -LiteralPath $testReport.FullName -Raw
            $testCount += [int]$testXml.testsuite.tests
            $failureCount += [int]$testXml.testsuite.failures + [int]$testXml.testsuite.errors
            $skippedCount += [int]$testXml.testsuite.skipped
        }
        if ($testCount -eq 0 -or $failureCount -gt 0 -or $skippedCount -gt 0) { throw "测试报告未满足执行标准：$testModule，tests=$testCount failures=$failureCount skipped=$skippedCount" }
        $testSummaries += [pscustomobject]@{ Module=$testModule; Tests=$testCount; Failures=$failureCount; Skipped=$skippedCount }
    }
    Write-VerificationText (Join-Path $attemptRoot 'tests.json') (ConvertTo-Json -InputObject $testSummaries)
    Complete-VerificationStage $testsReceipt @((Join-Path $attemptRoot 'tests.json'),
        (Join-Path $runRoot 'modules/log-core/test-results/test'), (Join-Path $runRoot 'modules/log-desktop/test-results/test'),
        (Join-Path $runRoot 'modules/log-sample/classes/java/main'))
}
if ('Android' -in $Stages) {
    # Android 构建证据包括 AAR/APK、R8 映射及隔离后的 schema。
    $androidReceipt = Start-VerificationStage 'Android'
    Initialize-VerificationDebugKey
    Invoke-GradleVerification 'android-build' @(':log-android:assembleDebug', ':log-android:assembleRelease', ':log-sample-android:assembleDebug', ':log-sample-android:assembleRelease')
    Complete-VerificationStage $androidReceipt @((Join-Path $runRoot 'modules/log-android/outputs/aar'),
        (Join-Path $runRoot 'modules/log-sample-android/outputs/apk'), (Join-Path $runRoot 'modules/log-sample-android/outputs/mapping/release'),
        (Join-Path $runRoot 'schemas'))
}
if ('Lint' -in $Stages) {
    # 四个变体的 XML 都必须存在；后续文件变化会使该阶段记录失效。
    $lintReceipt = Start-VerificationStage 'Lint'
    Invoke-GradleVerification 'android-lint' @(':log-android:lintDebug', ':log-android:lintRelease', ':log-sample-android:lintDebug', ':log-sample-android:lintRelease')
    Complete-VerificationStage $lintReceipt @((Join-Path $runRoot 'modules/log-android/reports/lint-results-debug.xml'),
        (Join-Path $runRoot 'modules/log-android/reports/lint-results-release.xml'),
        (Join-Path $runRoot 'modules/log-sample-android/reports/lint-results-debug.xml'),
        (Join-Path $runRoot 'modules/log-sample-android/reports/lint-results-release.xml'))
}
if ('Publish' -in $Stages) {
    # 记录实际发布仓库中的文件，供消费验证绑定准确的发布尝试。
    $publishReceipt = Start-VerificationStage 'Publish'
    Invoke-GradleVerification 'publish-local' @(':log-core:publishToMavenLocal', ':log-desktop:publishToMavenLocal', ':log-android:publishToMavenLocal')
    Complete-VerificationStage $publishReceipt @((Join-Path $runRoot 'm2/com/github/Atian10/log-record'))
}
if ('Consumers' -in $Stages) {
    # 当前发布未成功或内容变化时，不允许生成新的消费成功记录。
    $consumersReceipt = Start-VerificationStage 'Consumers' @('Publish')
    Initialize-VerificationDebugKey
    $consumerOutputs = @((Join-Path $runRoot 'consumers'))
    foreach ($mode in $consumerModes) {
        New-ConsumerProjects $mode
        $prefix = if ($mode -eq 'DefaultMaven') { 'consumer' } else { 'consumer-pom' }
        Invoke-GradleVerification "$mode-consume-core" @('classes','verifyRuntimeGraph') (Join-Path $runRoot "consumers/$prefix-core")
        Invoke-GradleVerification "$mode-consume-java" @('classes','verifyRuntimeGraph') (Join-Path $runRoot "consumers/$prefix-java")
        Invoke-GradleVerification "$mode-consume-android" @('assembleDebug','assembleRelease','verifyRuntimeGraph') (Join-Path $runRoot "consumers/$prefix-android")
        $consumerOutputs += @((Join-Path $runRoot "modules/$prefix-core/classes/java/main"), (Join-Path $runRoot "modules/$prefix-java/classes/java/main"),
            (Join-Path $runRoot "modules/$prefix-android/outputs/apk"), (Join-Path $runRoot "modules/$prefix-android/outputs/mapping/release"))
    }
    Complete-VerificationStage $consumersReceipt $consumerOutputs
}
if ('PublicationArtifacts' -in $Stages) {
    # 独立发布验收只承接本轮 Publish/Consumers，不降低下方完整 Artifacts 的前置要求。
    $publicationReceipt = Start-VerificationStage 'PublicationArtifacts' @('Publish','Consumers')
    $publicationChecks = @(& (Join-Path $PSScriptRoot 'check-verification-artifacts.ps1') -RunRoot $runRoot -Workspace $workspace -Scope Publication)
    # 发布会运行 Room 注解处理器；仅比较隔离 schema，不覆盖原文件。
    $schemaRelative = 'com.atian10.logrecord.android.room.LogDatabase/1.json'
    $schemaOriginal = Get-Content -LiteralPath (Join-Path $workspace "log-android/schemas/$schemaRelative") -Raw | ConvertFrom-Json -AsHashtable
    $schemaGenerated = Get-Content -LiteralPath (Join-Path $runRoot "schemas/$schemaRelative") -Raw | ConvertFrom-Json -AsHashtable
    if ((ConvertTo-Json -InputObject $schemaOriginal -Depth 100 -Compress) -cne
        (ConvertTo-Json -InputObject $schemaGenerated -Depth 100 -Compress)) { throw '发布生成的 Room schema 与源码不一致。' }
    Write-VerificationText (Join-Path $attemptRoot 'publication-checks.json') (ConvertTo-Json -InputObject $publicationChecks -Depth 8)
    # 单独记录本轮分发文件及消费者结果的身份，历史完整验收报告保持原样。
    $publicationPaths = @((Join-Path $runRoot 'm2/com/github/Atian10/log-record'))
    foreach ($prefix in @('consumer','consumer-pom')) {
        $publicationPaths += @((Join-Path $runRoot "modules/$prefix-core/classes/java/main"), (Join-Path $runRoot "modules/$prefix-java/classes/java/main"),
            (Join-Path $runRoot "modules/$prefix-android/outputs"))
    }
    $publicationHashes = @(Get-OutputHashes $publicationPaths)
    Write-VerificationText (Join-Path $attemptRoot 'publication-artifacts.json') (ConvertTo-Json -InputObject $publicationHashes -Depth 6)
    Complete-VerificationStage $publicationReceipt @((Join-Path $attemptRoot 'publication-checks.json'),
        (Join-Path $attemptRoot 'publication-artifacts.json'), (Join-Path $runRoot 'schemas'))
    Write-Output 'PASS publication-artifacts-and-schema'
}
if ('Artifacts' -in $Stages) {
    # 聚合验收必须先通过所有必需阶段的输入、依赖和输出校验，不能拼接旧产物。
    $artifactsReceipt = Start-VerificationStage 'Artifacts' @('Tests','Android','Lint','Publish','Consumers')
    # schema 比较保留完整结构，忽略 JSON 排版；不回写源码 schema。
    $schemaRelative = 'com.atian10.logrecord.android.room.LogDatabase/1.json'
    $schemaOriginal = Get-Content -LiteralPath (Join-Path $workspace "log-android/schemas/$schemaRelative") -Raw | ConvertFrom-Json -AsHashtable
    $schemaGenerated = Get-Content -LiteralPath (Join-Path $runRoot "schemas/$schemaRelative") -Raw | ConvertFrom-Json -AsHashtable
    $originalJson = ConvertTo-Json -InputObject $schemaOriginal -Depth 100 -Compress
    $generatedJson = ConvertTo-Json -InputObject $schemaGenerated -Depth 100 -Compress
    if ($originalJson -cne $generatedJson) { throw '生成的 Room schema 与源码定义不一致，需复核；未覆盖原文件。' }
    # 制品内容由独立只读检查器核验，错误直接阻止当前阶段成功记录。
    $artifactChecks = @(& (Join-Path $PSScriptRoot 'check-verification-artifacts.ps1') -RunRoot $runRoot -Workspace $workspace)
    Write-VerificationText (Join-Path $attemptRoot 'artifact-checks.json') (ConvertTo-Json -InputObject $artifactChecks -Depth 8)
    $artifactFiles = @(Get-ChildItem -LiteralPath (Join-Path $runRoot 'modules'),(Join-Path $runRoot 'm2') -Recurse -File | Where-Object { $_.Extension -in @('.jar','.aar','.apk','.pom','.module') -or $_.Name -eq 'mapping.txt' })
    $artifactHashes = @($artifactFiles | ForEach-Object { [pscustomobject]@{ Path=$_.FullName; Bytes=$_.Length; Sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } })
    Write-VerificationText (Join-Path $attemptRoot 'artifacts.json') (ConvertTo-Json -InputObject $artifactHashes -Depth 5)
    Complete-VerificationStage $artifactsReceipt @((Join-Path $attemptRoot 'artifacts.json'), (Join-Path $attemptRoot 'artifact-checks.json'))
    Write-Output 'PASS schema-and-artifact-hashes'
}
Write-Output "COMPLETED $attemptRoot"
} catch {
    $firstFailure = $_
    # 仅关闭本次尝试的 RUNNING 状态；不改写其他尝试或历史失败证据。
    try {
    foreach ($stage in $Stages) {
        $receiptPath = Join-Path $runRoot "receipts/$stage.json"
        if (!(Test-Path -LiteralPath $receiptPath)) { continue }
        $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json -AsHashtable
        if ($receipt.State -ne 'RUNNING' -or $receipt.Attempt -cne $attemptId) { continue }
        $receipt.State = 'FAILED'
        $receipt['FailedUtc'] = [DateTime]::UtcNow.ToString('o')
        $receipt['Error'] = $firstFailure.Exception.ToString()
        $receiptText = ConvertTo-Json -InputObject $receipt -Depth 8
        Write-VerificationText $receiptPath $receiptText
        Write-VerificationText (Join-Path $attemptRoot "$stage.receipt.json") $receiptText
    }
    } catch {
        # 记录介质失效不能覆盖已经取得的构建或验收首错。
        [Console]::Error.WriteLine("保存阶段失败记录时另有错误：$($_.Exception)")
    }
    throw $firstFailure
}
