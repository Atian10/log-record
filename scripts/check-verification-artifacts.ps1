#Requires -Version 7.0
<# 仅检查本轮已生成制品；不加载应用类、不安装或运行 APK。 #>
param(
    [Parameter(Mandatory)][string]$RunRoot,
    [Parameter(Mandatory)][string]$Workspace,
    # Publication 只验收发布和消费产物；默认 Full 保留原完整验收范围。
    [ValidateSet('Full','Publication')][string]$Scope = 'Full'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$version = '0.0.0-local-validation'
$group = 'com.github.Atian10.log-record'
$checks = [Collections.Generic.List[object]]::new()

function Assert-SourceEntries($Zip, [string]$Module) {
    # 本地包对应当前构建输入的原始字节；远端验收另以完整 Git 提交的 blob 比对。
    $sourceRoot = [IO.Path]::GetFullPath((Join-Path $Workspace "$Module/src/main/java"))
    $expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java') {
        $relative = [IO.Path]::GetRelativePath($sourceRoot,$file.FullName).Replace('\','/')
        $expected.Add($relative,$file.FullName)
    }
    $duplicates = @($Zip.Entries | Group-Object FullName | Where-Object Count -gt 1)
    if ($duplicates.Count) { throw "源码包包含重复条目：$Module" }
    $entries = @($Zip.Entries | Where-Object { $_.FullName.EndsWith('.java',[StringComparison]::Ordinal) })
    if (!$expected.Count -or $entries.Count -ne $expected.Count) { throw "源码包条目数量与当前输入不同：$Module" }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        foreach ($entry in $entries) {
            if (!$expected.ContainsKey($entry.FullName)) { throw "源码包包含额外或路径不一致的 Java 条目：$($entry.FullName)" }
            $stream = $entry.Open()
            try { $actualHash = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-','') }
            finally { $stream.Dispose() }
            $expectedHash = (Get-FileHash -LiteralPath $expected[$entry.FullName]).Hash
            if ($actualHash -cne $expectedHash) { throw "源码条目与本轮输入字节不一致：$Module/$($entry.FullName)" }
            $checks.Add([pscustomobject]@{ Check='SourceEntry'; Module=$Module; Entry=$entry.FullName; SourcePath=$expected[$entry.FullName]; SHA256=$actualHash; Basis='CurrentBuildInputBytes' })
        }
    } finally { $hasher.Dispose() }
}

function Require-File([string]$RelativePath) {
    $path = Join-Path $RunRoot $RelativePath
    if (!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
        throw "缺少本轮必要制品：$path"
    }
    return $path
}

function Read-ZipText($Zip, [string]$Name) {
    $entry = $Zip.GetEntry($Name)
    if (!$entry) { throw "制品缺少条目：$Name" }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Assert-License($Zip, [string]$Module) {
    # 一个模块只能携带一份自己的许可条目，文本必须与根许可证一致。
    $entryName = "META-INF/log-record/$Module/LICENSE"
    $entries = @($Zip.Entries | Where-Object { $_.FullName -ceq $entryName })
    if ($entries.Count -ne 1) { throw "许可证条目缺失或重复：$entryName" }
    $actualLicense = Read-ZipText $Zip $entryName
    $rootLicense = Get-Content -LiteralPath (Join-Path $Workspace 'LICENSE') -Raw
    if (($actualLicense -replace '\r\n', "`n") -cne ($rootLicense -replace '\r\n', "`n")) {
        throw "分发许可与根许可证不一致：$Module"
    }
}

function Assert-Java11Classes($Zip, [string]$Label) {
    # 直接读取 class 头，不执行库代码；每一个本项目 class 都应为 major 55。
    $count = 0
    foreach ($entry in $Zip.Entries) {
        if (!$entry.FullName.EndsWith('.class')) { continue }
        $stream = $entry.Open()
        try {
            $header = [byte[]]::new(8)
            $read = 0
            while ($read -lt 8) {
                $bytesRead = $stream.Read($header, $read, 8 - $read)
                if ($bytesRead -eq 0) { throw "不完整 class：$($entry.FullName)" }
                $read += $bytesRead
            }
            $major = [int]$header[6] * 256 + [int]$header[7]
            if ([BitConverter]::ToString($header, 0, 4) -ne 'CA-FE-BA-BE' -or $major -ne 55) {
                throw "字节码不符合 Java 11：$Label/$($entry.FullName)，major=$major"
            }
            $count++
        } finally { $stream.Dispose() }
    }
    if ($count -eq 0) { throw "未发现 class：$Label" }
    $checks.Add([pscustomobject]@{ Check='Java11Classes'; Artifact=$Label; Classes=$count; Major=55 })
}

function Assert-DocumentationExamples([string]$Prefix) {
    foreach ($platform in @('android','java')) {
        $receiptPath = Require-File "consumers/$Prefix-$platform/documentation-examples.json"
        $examples = @(Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json)
        # 包住整个分支输出，避免 Desktop 单元素被 PowerShell 解包成无 Count 的字符串。
        $expectedMarkers = @(if ($platform -eq 'android') { 'android-init','android-manifest','readme-android-init','javadoc-init','exception-query','android-export' } else { 'javadoc-init' })
        if ($examples.Count -ne $expectedMarkers.Count -or @($examples | Group-Object Marker | Where-Object Count -ne 1).Count) { throw "文档示例数量或唯一性错误：$receiptPath" }
        foreach ($example in $examples) {
            if ($example.Marker -notin $expectedMarkers) { throw "未知示例标记：$($example.Marker)" }
            $expectedSource = switch ($example.Marker) {
                'readme-android-init' { 'README.md' }
                'javadoc-init' { if ($platform -eq 'android') { 'log-android/src/main/java/com/atian10/logrecord/android/AndroidLogInit.java' } else { 'log-desktop/src/main/java/com/atian10/logrecord/desktop/DesktopLogInit.java' } }
                default { 'docs/使用文档.md' }
            }
            if ($example.Source -cne $expectedSource -or $example.SourceSha256 -cne (Get-FileHash -LiteralPath (Join-Path $Workspace $expectedSource)).Hash) { throw "示例不对应当前文档：$receiptPath" }
            $generated = [IO.Path]::GetFullPath($example.GeneratedFile)
            $consumerRoot = [IO.Path]::GetFullPath((Join-Path $RunRoot "consumers/$Prefix-$platform")) + [IO.Path]::DirectorySeparatorChar
            if (!$generated.StartsWith($consumerRoot,[StringComparison]::OrdinalIgnoreCase) -or
                $example.GeneratedSha256 -cne (Get-FileHash -LiteralPath $generated).Hash -or
                [string]::IsNullOrWhiteSpace($example.Snippet) -or !(Get-Content -LiteralPath $generated -Raw).Contains([string]$example.Snippet)) { throw "生成示例已变化或未保留原始调用：$receiptPath" }
            if ($example.Marker -ne 'android-manifest') {
                $className = [IO.Path]::GetFileNameWithoutExtension($generated)
                $classRelative = if ($platform -eq 'android') { "modules/$Prefix-android/intermediates/javac/debug/classes/com/atian10/logrecord/verification/$className.class" } else { "modules/$Prefix-java/classes/java/main/$className.class" }
                $compiledClass = Require-File $classRelative
                $checks.Add([pscustomobject]@{ Check='DocumentationCompilation'; Consumer="$Prefix-$platform"; Source=$example.Source; Marker=$example.Marker; SourceSha256=$example.SourceSha256; Generated=$generated; CompiledClass=$compiledClass; ClassSha256=(Get-FileHash -LiteralPath $compiledClass).Hash; Executed=$false })
            }
        }
    }
}

function Assert-ConsumerManifestCases([string]$Prefix) {
    $receiptPath = Require-File "consumers/$Prefix-android/manifest-cases.json"
    $cases = @(Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json)
    $expectedValues = @{ backupTrue='true'; backupFalse='false'; backupOmitted='' }
    if ($cases.Count -ne 3 -or @($cases | Group-Object Variant | Where-Object Count -ne 1).Count) { throw "Manifest 用例不完整：$receiptPath" }
    foreach ($case in $cases) {
        if (!$expectedValues.ContainsKey($case.Variant)) { throw "未知 Manifest 用例：$receiptPath" }
        $path = Require-File "modules/$Prefix-android/intermediates/merged_manifest/$($case.Variant)/AndroidManifest.xml"
        [xml]$manifest = Get-Content -LiteralPath $path -Raw
        $application = $manifest.SelectSingleNode('/manifest/application')
        $expected = $expectedValues[$case.Variant]
        if (!$application -or $case.Path -cne $path -or $case.Sha256 -cne (Get-FileHash -LiteralPath $path).Hash -or
            $case.Expected -cne $expected -or $case.Actual -cne $expected -or
            $application.GetAttribute('allowBackup','http://schemas.android.com/apk/res/android') -cne $expected -or
            $application.GetAttribute('name','http://schemas.android.com/apk/res/android') -cne 'com.atian10.logrecord.verification.MyApp') { throw "Manifest 属性或 Application 注册不一致：$path" }
        $checks.Add([pscustomobject]@{ Check='ConsumerManifest'; Consumer="$Prefix-android"; Variant=$case.Variant; AllowBackup=$expected; Manifest=$path; Sha256=$case.Sha256; Application='com.atian10.logrecord.verification.MyApp'; ApplicationExecuted=$false })
    }
}

foreach ($module in @('log-core','log-desktop','log-android')) {
    $repositoryPath = "m2/com/github/Atian10/log-record/$module/$version/$module-$version"
    $extension = if ($module -eq 'log-android') { 'aar' } else { 'jar' }
    $artifact = Require-File "$repositoryPath.$extension"
    $sources = Require-File "$repositoryPath-sources.jar"
    $pomPath = Require-File "$repositoryPath.pom"
    $versionDirectory = Split-Path -Parent $pomPath
    if (@(Get-ChildItem -LiteralPath $versionDirectory -Filter '*.module' -File).Count) { throw "POM 发布策略不允许分发 .module：$module" }
    $pomText = Get-Content -LiteralPath $pomPath -Raw
    if ($pomText -match 'published-with-gradle-metadata|do_not_remove:.*gradle') { throw "POM 仍包含 Gradle Metadata 重定向标记：$module" }
    [xml]$pom = $pomText
    if ($pom.project.groupId -ne $group -or $pom.project.artifactId -ne $module -or $pom.project.version -ne $version) {
        throw "POM 坐标不一致：$module"
    }
    $packagingNode = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="packaging"]')
    $packaging = if ($packagingNode) { $packagingNode.InnerText } else { 'jar' }
    if ($packaging -cne $extension) { throw "POM 包类型错误：$module/$packaging" }
    # 除依赖坐标之外，独立分发的 POM 还应能识别项目、源码及自身许可。
    if ([string]::IsNullOrWhiteSpace($pom.project.name) -or
        [string]::IsNullOrWhiteSpace($pom.project.description) -or
        $pom.project.url -ne 'https://github.com/Atian10/log-record' -or
        $pom.project.scm.url -ne 'https://github.com/Atian10/log-record' -or
        $pom.project.scm.connection -ne 'scm:git:https://github.com/Atian10/log-record.git' -or
        $pom.project.licenses.license.name -ne 'MIT License' -or
        $pom.project.licenses.license.url -ne 'https://opensource.org/license/mit' -or
        $pom.project.licenses.license.distribution -ne 'repo') {
        throw "POM 项目或许可信息不完整：$module"
    }
    $expected = switch ($module) {
        'log-core' { @('com.google.code.gson:gson:2.10.1:compile') }
        'log-desktop' { @("${group}:log-core:${version}:compile", 'org.xerial:sqlite-jdbc:3.42.0.0:runtime') }
        'log-android' { @("${group}:log-core:${version}:compile", 'androidx.room:room-runtime:2.5.2:runtime', 'androidx.annotation:annotation:1.6.0:runtime',
            'org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0:runtime', 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0:runtime') }
    }
    $actual = @($pom.project.dependencies.dependency | ForEach-Object { "$($_.groupId):$($_.artifactId):$($_.version):$($_.scope)" })
    if (@(Compare-Object ($expected | Sort-Object) ($actual | Sort-Object)).Count -gt 0) {
        throw "POM 依赖不符合约定：$module，$actual"
    }
    if ($module -eq 'log-android') {
        $bomNodes = @($pom.SelectNodes('/*[local-name()="project"]/*[local-name()="dependencyManagement"]/*[local-name()="dependencies"]/*[local-name()="dependency"]'))
        if ($bomNodes.Count -ne 1) { throw 'Android POM 必须具有唯一 Kotlin BOM 导入项。' }
        $bom = $bomNodes[0]
        if ($bom.groupId -ne 'org.jetbrains.kotlin' -or $bom.artifactId -ne 'kotlin-bom' -or
            $bom.version -ne '1.8.0' -or $bom.type -ne 'pom' -or $bom.scope -ne 'import') {
            throw 'Android POM 未传递 Kotlin 1.8.0 BOM。'
        }
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        if ($module -eq 'log-android') {
            $rules = Read-ZipText $zip 'proguard.txt'
            $sourceRules = Get-Content -LiteralPath (Join-Path $Workspace 'log-android/consumer-rules.pro') -Raw
            if (($rules -replace '\r\n', "`n").Trim() -cne ($sourceRules -replace '\r\n', "`n").Trim()) { throw 'AAR 未完整携带 consumer rules。' }
            $manifest = Read-ZipText $zip 'AndroidManifest.xml'
            if ($manifest -notmatch 'minSdkVersion="21"') { throw 'AAR minSdk 与固定要求不同。' }
            [xml]$aarManifest = $manifest
            $application = $aarManifest.SelectSingleNode('/manifest/application')
            if ($application -and $application.HasAttribute('allowBackup','http://schemas.android.com/apk/res/android')) { throw '库 AAR 仍在设置消费应用的 allowBackup。' }
            $checks.Add([pscustomobject]@{ Check='LibraryBackupPolicyAbsent'; Artifact=$artifact; AllowBackupAbsent=$true })
            $classesEntry = $zip.GetEntry('classes.jar')
            if (!$classesEntry) { throw 'AAR 缺少 classes.jar。' }
            $classesStream = $classesEntry.Open()
            $memoryStream = [IO.MemoryStream]::new()
            try {
                $classesStream.CopyTo($memoryStream)
                $memoryStream.Position = 0
                $classesZip = [IO.Compression.ZipArchive]::new($memoryStream, [IO.Compression.ZipArchiveMode]::Read, $true)
                try {
                    Assert-Java11Classes $classesZip $module
                    Assert-License $classesZip $module
                } finally { $classesZip.Dispose() }
            } finally { $memoryStream.Dispose(); $classesStream.Dispose() }
        } else {
            Assert-Java11Classes $zip $module
            Assert-License $zip $module
        }
    } finally { $zip.Dispose() }
    $sourceZip = [IO.Compression.ZipFile]::OpenRead($sources)
    try {
        Assert-SourceEntries $sourceZip $module
        Assert-License $sourceZip $module
    } finally { $sourceZip.Dispose() }
    $checks.Add([pscustomobject]@{ Check='Publication'; Module=$module; MetadataPolicy='Pom'; ModuleMetadataAbsent=$true; MarkerAbsent=$true; Packaging=$packaging; Dependencies=$actual; Sources=$sources; License='MIT'; LicenseEntry="META-INF/log-record/$module/LICENSE" })
}

# 六个消费者必须取得本轮 POM、sources 与二进制；POM 的缺席不能仅由构建成功推导。
foreach ($mode in @('DefaultMaven','PomOnly')) {
    $prefix = if ($mode -eq 'DefaultMaven') { 'consumer' } else { 'consumer-pom' }
    Assert-DocumentationExamples $prefix
    Assert-ConsumerManifestCases $prefix
    foreach ($entry in @(@('core','log-core'),@('java','log-desktop'),@('android','log-android'))) {
        $receiptPath = Require-File "consumers/$prefix-$($entry[0])/resolved-artifacts.json"
        $receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
        if ($receipt.mode -cne $mode -or $receipt.module -cne $entry[1] -or $receipt.publicationMetadata -cne 'Pom') { throw "消费者身份或发布策略错误：$receiptPath" }
        $expectedModules = if ($entry[1] -eq 'log-core') { @('log-core') } else { @('log-core',$entry[1]) }
        $projectEntries = @($receipt.artifacts | Where-Object group -eq $group)
        foreach ($module in $expectedModules) {
            foreach ($kind in @('pom','sources','binary')) {
                $matches = @($projectEntries | Where-Object { $_.module -eq $module -and
                    (($kind -eq 'pom' -and $_.configuration -eq 'verificationPoms') -or
                     ($kind -eq 'sources' -and $_.configuration -eq 'verificationSources') -or
                     ($kind -eq 'binary' -and $_.configuration -match '(?i)runtimeClasspath$')) })
                if (!$matches.Count) { throw "消费者缺少 $module/$kind 实际解析证据：$mode" }
                foreach ($artifact in $matches) {
                    $suffix = switch ($kind) { 'pom' { '.pom' }; 'sources' { '-sources.jar' }; 'binary' { if ($module -eq 'log-android') { '.aar' } else { '.jar' } } }
                    $expectedPath = Require-File "m2/com/github/Atian10/log-record/$module/$version/$module-$version$suffix"
                    if ([IO.Path]::GetFullPath($artifact.path) -cne [IO.Path]::GetFullPath($expectedPath) -or $artifact.version -cne $version -or
                        $artifact.sha256 -ine (Get-FileHash -LiteralPath $expectedPath).Hash) { throw "消费者文件来源或哈希不同：$mode/$module/$kind" }
                }
            }
        }
        if ($entry[1] -eq 'log-android') {
            $bom = @($receipt.artifacts | Where-Object configuration -eq 'verificationBom')
            if ($bom.Count -ne 1 -or $bom[0].group -cne 'org.jetbrains.kotlin' -or $bom[0].module -cne 'kotlin-bom' -or $bom[0].version -cne '1.8.0' -or
                $bom[0].extension -cne 'pom' -or $bom[0].sha256 -ine (Get-FileHash -LiteralPath $bom[0].path).Hash) { throw "缺少已核对的 BOM POM 来源：$mode" }
        }
        $checks.Add([pscustomobject]@{ Check='ConsumerResolution'; Mode=$mode; Module=$entry[1]; Receipt=$receiptPath; Offline=$receipt.offline; GradleHome=$receipt.gradleHome; MetadataPolicy='Pom' })
    }
# 发布消费检查两种 Android 变体，确认 R8 打包未丢失 core/Android 的独立许可。
foreach ($variant in @('debug','release')) {
    $apkName = if ($variant -eq 'debug') { "$prefix-android-debug.apk" } else { "$prefix-android-release-unsigned.apk" }
    $consumerApk = Require-File "modules/$prefix-android/outputs/apk/$variant/$apkName"
    $consumerZip = [IO.Compression.ZipFile]::OpenRead($consumerApk)
    try {
        if (!$consumerZip.GetEntry('AndroidManifest.xml') -or !$consumerZip.GetEntry('classes.dex')) { throw "消费 APK 内容不完整：$variant" }
        Assert-License $consumerZip 'log-core'
        Assert-License $consumerZip 'log-android'
    } finally { $consumerZip.Dispose() }
    $checks.Add([pscustomobject]@{ Check='ConsumerApkAndLicenses'; Variant=$variant; Artifact=$consumerApk })
}
foreach ($consumer in @("$prefix-core","$prefix-java")) {
    $consumerClass = Require-File "modules/$consumer/classes/java/main/Consumer.class"
    $checks.Add([pscustomobject]@{ Check='ConsumerCompilation'; Consumer=$consumer; Artifact=$consumerClass })
}
$consumerMapping = Require-File "modules/$prefix-android/outputs/mapping/release/mapping.txt"
$consumerConfiguration = Require-File "modules/$prefix-android/outputs/mapping/release/configuration.txt"
if ((Get-Content -LiteralPath $consumerConfiguration -Raw) -notmatch 'com\.atian10\.logrecord\.android\.room\.\*\*') {
    throw '消费方 R8 未取得 AAR 的 Room 规则。'
}
$checks.Add([pscustomobject]@{ Check='ConsumerR8'; Artifact=$consumerMapping })
}

if ($Scope -eq 'Publication') {
    # 限定入口不伪造 Tests/Android/Lint 的完整验收结论，也不读取旧报告补齐。
    $checks.ToArray()
    return
}

foreach ($relative in @(
    'modules/log-android/outputs/aar/log-android-debug.aar',
    'modules/log-android/outputs/aar/log-android-release.aar',
    'modules/log-sample-android/outputs/apk/debug/log-sample-android-debug.apk',
    'modules/log-sample-android/outputs/apk/release/log-sample-android-release-unsigned.apk',
    'modules/consumer-android/outputs/apk/debug/consumer-android-debug.apk')) {
    $path = Require-File $relative
    if ($relative.EndsWith('.apk')) {
        $zip = [IO.Compression.ZipFile]::OpenRead($path)
        try {
            if (!$zip.GetEntry('AndroidManifest.xml') -or !$zip.GetEntry('classes.dex')) { throw "APK 内容不完整：$relative" }
        } finally { $zip.Dispose() }
    }
    $checks.Add([pscustomobject]@{ Check='PackageExists'; Artifact=$relative })
}
$mapping = Require-File 'modules/log-sample-android/outputs/mapping/release/mapping.txt'
$configuration = Require-File 'modules/log-sample-android/outputs/mapping/release/configuration.txt'
if ((Get-Content -LiteralPath $configuration -Raw) -notmatch 'com\.atian10\.logrecord\.android\.room\.\*\*') { throw 'R8 实际配置中缺少 AAR 传递的 Room 规则。' }
$checks.Add([pscustomobject]@{ Check='R8MappingAndRules'; Artifact=$mapping })

foreach ($module in @('log-android','log-sample-android')) {
    foreach ($variant in @('debug','release')) {
        $report = Require-File "modules/$module/reports/lint-results-$variant.xml"
        [xml]$lint = Get-Content -LiteralPath $report -Raw
        $issues = @($lint.SelectNodes('/issues/issue'))
        if (@($issues | Where-Object { $_.severity -in @('Error','Fatal') }).Count -gt 0) { throw "Lint 含错误：$module/$variant" }
        $checks.Add([pscustomobject]@{ Check='Lint'; Module=$module; Variant=$variant; Warnings=@($issues | Where-Object { $_.severity -eq 'Warning' }).Count })
    }
}
$checks.ToArray()
