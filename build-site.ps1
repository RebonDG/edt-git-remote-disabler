# Сборка p2 update site для установки через «Справка → Установить новое ПО».
#
# Использует headless-приложения Eclipse, которые уже есть в установленном EDT —
# отдельный Tycho/Maven не нужен:
#
#   1. FeaturesAndBundlesPublisher — публикует бандл и feature
#   2. CategoryPublisher            — добавляет категорию (иначе «Uncategorized»)
#
# Результат: build\site\ — то, что выкладывается на GitHub Pages.

param(
    [string]$EdtHome = '',
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'common.ps1')
if (-not $JdkHome) { $JdkHome = Get-DefaultJdkHome }
if (-not $EdtHome) { $EdtHome = Get-DefaultEdtHome }
$work = Join-Path $root 'build\p2'
$site = Join-Path $root 'build\site'
$src = Join-Path $work 'source'

# 1. Бандл.
& (Join-Path $root 'build.ps1') -JdkHome $JdkHome | Out-Null
$bundleJar = Get-ChildItem (Join-Path $root 'build\dist') -Filter 'ru.edt.gitremotedisabler_*.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $bundleJar) { throw "Не найден собранный бандл в build\dist" }
$version = [regex]::Match((Split-Path $bundleJar -Leaf), '_(\d+\.\d+\.\d+)\.jar$').Groups[1].Value
Write-Host "Публикую версию $version"

# 2. Каталог-источник для publisher: plugins/ + features/.
foreach ($d in @($work, $site)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path (Join-Path $src 'plugins'), (Join-Path $src 'features'), $site | Out-Null
Copy-Item $bundleJar (Join-Path $src 'plugins') -Force

# Feature: версия подставляется из бандла, чтобы не расходились.
$featureXml = Get-Content (Join-Path $root 'feature\feature.xml') -Raw
$featureXml = $featureXml -replace 'version="\d+\.\d+\.\d+"', "version=`"$version`""
$featureStage = Join-Path $root 'build\feature'
if (Test-Path $featureStage) { Remove-Item $featureStage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $featureStage | Out-Null
Set-Content -Path (Join-Path $featureStage 'feature.xml') -Value $featureXml -Encoding UTF8
Copy-Item (Join-Path $root 'feature\build.properties') $featureStage -Force

$featureJar = Join-Path $src "features\ru.edt.gitremotedisabler.feature_$version.jar"
Push-Location $featureStage
try {
    & (Join-Path $JdkHome 'bin\jar.exe') --create --file $featureJar -C . .
    if ($LASTEXITCODE -ne 0) { throw "Не удалось собрать feature jar" }
} finally { Pop-Location }

# 3. Запуск headless-приложений Eclipse из установленного EDT.
$launcher = Join-Path $EdtHome '1cedt.exe'
$configuration = (Join-Path $EdtHome 'configuration').Replace('\', '/')
$workUri = $work.Replace('\', '/')

function Wait-ForRepo {
    param([string]$Repo, [int]$Seconds = 180)
    $content = Join-Path $Repo 'content.jar'
    $artifacts = Join-Path $Repo 'artifacts.jar'
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $content) -and (Test-Path $artifacts)) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# Шаг 1: бандл + feature.
# Внимание: -source и -categoryDefinition принимают обычный путь к каталогу,
# а -metadataRepository/-artifactRepository — URI вида file:/...
$metaUri = "file:$workUri/repo"
& $launcher -nosplash -consoleLog -configuration "file:$configuration" `
    -data "file:$workUri/ws" `
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -metadataRepository $metaUri -artifactRepository $metaUri `
    -source $workUri/source -compress -publishArtifacts | Out-Null

if (-not (Wait-ForRepo (Join-Path $work 'repo') 180)) {
    throw "FeaturesAndBundlesPublisher не создал репозиторий в $work\repo"
}
Write-Host "  репозиторий создан"

# Шаг 2: категория.
# Используется site.xml, а не category.xml: для category.xml p2 строит id
# категории из локального пути к файлу (file:/...), и этот путь попадает
# в опубликованные метаданные. Для site.xml id берётся из category-def/@name.
$categoryStage = Join-Path $root 'build\category'
if (Test-Path $categoryStage) { Remove-Item $categoryStage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $categoryStage | Out-Null
$siteXml = (Get-Content (Join-Path $root 'site.xml') -Raw) -replace '_1\.\d+\.\d+\.jar', "_$version.jar" -replace 'version="\d+\.\d+\.\d+"', "version=`"$version`""
Set-Content -Path (Join-Path $categoryStage 'site.xml') -Value $siteXml -Encoding UTF8
Copy-Item $categoryStage $work -Recurse -Force

$contentJarBefore = (Get-Item (Join-Path $work 'repo\content.jar')).LastWriteTime

# Внимание: -categoryDefinition принимает URI (file:/...), а -source — обычный путь.
& $launcher -nosplash -consoleLog -configuration "file:$configuration" `
    -data "file:$workUri/ws-cat" `
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher `
    -metadataRepository $metaUri `
    -categoryDefinition "file:$workUri/category/site.xml" -compress | Out-Null

# Ждём обновления content.jar после добавления категории.
$deadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $deadline) {
    $now = (Get-Item (Join-Path $work 'repo\content.jar') -ErrorAction SilentlyContinue)
    if ($now -and $now.LastWriteTime -gt $contentJarBefore) { break }
    Start-Sleep -Milliseconds 500
}

# 4. Копируем готовый репозиторий в build\site (его и публикуем).
Copy-Item (Join-Path $work 'repo\*') $site -Recurse -Force
# Вспомогательные файлы p2 для GitHub Pages не нужны.
Remove-Item (Join-Path $site '*.xml') -Force -ErrorAction SilentlyContinue

# 5. Чистим локальные пути из метаданных.
# Publisher кладёт абсолютные пути в НЕСКОЛЬКО мест:
#   content.xml   — id категории (из пути к site.xml) и name репозитория;
#   artifacts.xml — name репозитория.
# Поэтому обрабатываем оба файла, а не только content.xml.
$jarExe = Join-Path $JdkHome 'bin\jar.exe'
$fixDir = Join-Path $root 'build\fixup'
if (Test-Path $fixDir) { Remove-Item $fixDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $fixDir | Out-Null
Push-Location $fixDir
try {
    Copy-Item (Join-Path $site 'content.jar') .
    Copy-Item (Join-Path $site 'artifacts.jar') .
    & $jarExe xf content.jar
    & $jarExe xf artifacts.jar

    # --- content.xml ---
    $content = Get-Content 'content.xml' -Raw
    $leak = [regex]::Match($content, "<unit id='(file:[^']*?\.[^'.]+)'")
    if ($leak.Success) {
        $oldId = $leak.Groups[1].Value
        $newId = 'ru.edt.gitremotedisabler.category'
        Write-Host "  id категории: $oldId -> $newId"
        $content = $content.Replace($oldId, $newId)
    }
    $content = [regex]::Replace($content, "name='file:/[^']*'", "name='Git Remote Disabler Update Site'")
    Set-Content -Path 'content.xml' -Value $content -Encoding UTF8 -NoNewline

    # --- artifacts.xml ---
    $artifacts = Get-Content 'artifacts.xml' -Raw
    $artifacts = [regex]::Replace($artifacts, "name='file:/[^']*'", "name='Git Remote Disabler Update Site'")
    Set-Content -Path 'artifacts.xml' -Value $artifacts -Encoding UTF8 -NoNewline

    Remove-Item content.jar, artifacts.jar -Force
    & $jarExe --create --file content.jar -C . content.xml
    if ($LASTEXITCODE -ne 0) { throw "Не удалось перепаковать content.jar" }
    & $jarExe --create --file artifacts.jar -C . artifacts.xml
    if ($LASTEXITCODE -ne 0) { throw "Не удалось перепаковать artifacts.jar" }
    Copy-Item content.jar, artifacts.jar $site -Force
} finally { Pop-Location }

# 6. Проверка: ни локальных путей ни в одном файле метаданных.
$verify = Join-Path $root 'build\verify'
if (Test-Path $verify) { Remove-Item $verify -Recurse -Force }
New-Item -ItemType Directory -Force -Path $verify | Out-Null
Push-Location $verify
try {
    Copy-Item (Join-Path $site 'content.jar'), (Join-Path $site 'artifacts.jar') .
    & $jarExe xf content.jar
    & $jarExe xf artifacts.jar
    $content = Get-Content 'content.xml' -Raw
    $artifacts = Get-Content 'artifacts.xml' -Raw
} finally { Pop-Location }

if ($content -notmatch 'ru\.edt\.gitremotedisabler\.feature') {
    throw "В content.xml нет feature — сайт не соберётся корректно"
}

# Локальный путь не должен попадать НИ в content.xml, НИ в artifacts.xml.
# Проверяются оба: однажды artifacts.xml уже проскочил мимо проверки.
$leaks = @()
foreach ($pair in @(@('content.xml', $content), @('artifacts.xml', $artifacts))) {
    $name = $pair[0]
    $text = $pair[1]
    foreach ($m in [regex]::Matches($text, 'file:/[A-Za-z]:|[A-Za-z]:[/\\][^''"<>]{2,}')) {
        $leaks += "$name : $($m.Value)"
    }
}
if ($leaks.Count -gt 0) {
    throw ("В метаданных остались локальные пути — публиковать нельзя:`n  " + ($leaks -join "`n  "))
}
if ($content -notmatch 'type.category') {
    Write-Warning "Категория не найдена в content.xml — в диалоге установки будет 'Uncategorized'"
}

Write-Host ""
Write-Host "Готово: $site"
Get-ChildItem $site -Recurse -File | ForEach-Object {
    "  {0}  ({1:N0} B)" -f $_.FullName.Replace("$site\", ''), $_.Length
}
