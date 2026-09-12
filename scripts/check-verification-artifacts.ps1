#Requires -Version 7.0
<# 仅检查本轮已生成制品；不加载应用类、不安装或运行 APK。 #>
param([Parameter(Mandatory)][string]$RunRoot, [Parameter(Mandatory)][string]$Workspace)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$version = '0.0.0-local-validation'
$group = 'com.github.Atian10.log-record'
$checks = [Collections.Generic.List[object]]::new()

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

foreach ($module in @('log-core','log-desktop','log-android')) {
    $repositoryPath = "m2/com/github/Atian10/log-record/$module/$version/$module-$version"
    $extension = if ($module -eq 'log-android') { 'aar' } else { 'jar' }
    $artifact = Require-File "$repositoryPath.$extension"
    $sources = Require-File "$repositoryPath-sources.jar"
    $pomPath = Require-File "$repositoryPath.pom"
    $metadataPath = Require-File "$repositoryPath.module"
    [xml]$pom = Get-Content -LiteralPath $pomPath -Raw
    if ($pom.project.groupId -ne $group -or $pom.project.artifactId -ne $module -or $pom.project.version -ne $version) {
        throw "POM 坐标不一致：$module"
    }
    $expected = switch ($module) {
        'log-core' { @('com.google.code.gson:gson:2.10.1:compile') }
        'log-desktop' { @("${group}:log-core:${version}:compile", 'org.xerial:sqlite-jdbc:3.42.0.0:runtime') }
        'log-android' { @("${group}:log-core:${version}:compile", 'androidx.room:room-runtime:2.5.2:runtime', 'androidx.annotation:annotation:1.6.0:runtime') }
    }
    $actual = @($pom.project.dependencies.dependency | ForEach-Object { "$($_.groupId):$($_.artifactId):$($_.version):$($_.scope)" })
    if (@(Compare-Object ($expected | Sort-Object) ($actual | Sort-Object)).Count -gt 0) {
        throw "POM 依赖不符合约定：$module，$actual"
    }
    if ($module -eq 'log-android') {
        $bom = $pom.project.dependencyManagement.dependencies.dependency
        if ($bom.groupId -ne 'org.jetbrains.kotlin' -or $bom.artifactId -ne 'kotlin-bom' -or
            $bom.version -ne '1.8.0' -or $bom.type -ne 'pom' -or $bom.scope -ne 'import') {
            throw 'Android POM 未传递 Kotlin 1.8.0 BOM。'
        }
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json -AsHashtable
    if ($metadata.component.group -ne $group -or $metadata.component.module -ne $module -or $metadata.component.version -ne $version) {
        throw "Gradle metadata 坐标不一致：$module"
    }
    $runtimeVariants = @($metadata.variants | Where-Object { $_.attributes['org.gradle.usage'] -eq 'java-runtime' -and $_.attributes['org.gradle.category'] -eq 'library' })
    $apiVariants = @($metadata.variants | Where-Object { $_.attributes['org.gradle.usage'] -eq 'java-api' -and $_.attributes['org.gradle.category'] -eq 'library' })
    if ($runtimeVariants.Count -ne 1 -or $apiVariants.Count -ne 1) { throw "缺少唯一 API/runtime 发布变体：$module" }
    if ($module -ne 'log-android' -and $runtimeVariants[0].attributes['org.gradle.jvm.version'] -ne 11) { throw "metadata 未声明 Java 11：$module" }
    $zip = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        if ($module -eq 'log-android') {
            $rules = Read-ZipText $zip 'proguard.txt'
            $sourceRules = Get-Content -LiteralPath (Join-Path $Workspace 'log-android/consumer-rules.pro') -Raw
            if (($rules -replace '\r\n', "`n").Trim() -cne ($sourceRules -replace '\r\n', "`n").Trim()) { throw 'AAR 未完整携带 consumer rules。' }
            $manifest = Read-ZipText $zip 'AndroidManifest.xml'
            if ($manifest -notmatch 'minSdkVersion="21"') { throw 'AAR minSdk 与固定要求不同。' }
            $classesEntry = $zip.GetEntry('classes.jar')
            if (!$classesEntry) { throw 'AAR 缺少 classes.jar。' }
            $classesStream = $classesEntry.Open()
            $memoryStream = [IO.MemoryStream]::new()
            try {
                $classesStream.CopyTo($memoryStream)
                $memoryStream.Position = 0
                $classesZip = [IO.Compression.ZipArchive]::new($memoryStream, [IO.Compression.ZipArchiveMode]::Read, $true)
                try { Assert-Java11Classes $classesZip $module } finally { $classesZip.Dispose() }
            } finally { $memoryStream.Dispose(); $classesStream.Dispose() }
        } else { Assert-Java11Classes $zip $module }
    } finally { $zip.Dispose() }
    $sourceZip = [IO.Compression.ZipFile]::OpenRead($sources)
    try { if (@($sourceZip.Entries | Where-Object { $_.FullName.EndsWith('.java') }).Count -eq 0) { throw "源码制品为空：$module" } } finally { $sourceZip.Dispose() }
    $checks.Add([pscustomobject]@{ Check='Publication'; Module=$module; Dependencies=$actual; Sources=$sources })
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
