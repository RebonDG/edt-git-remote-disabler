# Сборка плагина Git Remote Disabler для 1C:EDT.
#
# Компиляция идёт напрямую javac против бандлов установленного EDT: отдельный
# Maven/Tycho здесь не нужен, для одного бандла он только замедляет цикл.
# Сборка p2-репозитория и feature — в build-p2.ps1.
#
# Параметры:
#   -EdtHome  каталог установки EDT (где лежит plugins/)
#   -JdkHome  JDK 21+ с javac (по умолчанию JBR из Android Studio либо Axiom JDK)

param(
    [string]$EdtHome = '',
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'common.ps1')

$bundle = Join-Path $root 'bundle'
$outDir = Join-Path $root 'build\classes'
$distDir = Join-Path $root 'build\dist'

if (-not $JdkHome) { $JdkHome = Get-DefaultJdkHome }
if (-not $EdtHome) { $EdtHome = Get-DefaultEdtHome }

$jdk = $JdkHome
$javac = Join-Path $jdk 'bin\javac.exe'
$jar = Join-Path $jdk 'bin\jar.exe'

if (-not (Test-Path $javac)) { throw "Не найден javac в $jdk" }
if (-not (Test-Path (Join-Path $EdtHome 'plugins'))) {
    throw "Не найден каталог plugins в EDT: $EdtHome"
}

Write-Host "JDK:  $jdk"
Write-Host "EDT:  $EdtHome"

# Пул бандлов EDT: собственные плагины установки + общий пул p2.
$searchDirs = Get-BundleSearchDirs -EdtHome $EdtHome

$needed = @(
    'org.eclipse.core.runtime_*',
    'org.eclipse.core.commands_*',
    'org.eclipse.core.expressions_*',
    'org.eclipse.core.jobs_*',
    'org.eclipse.equinox.common_*',
    'org.eclipse.equinox.registry_*',
    'org.eclipse.osgi_*',
    'org.eclipse.jface_*',
    'org.eclipse.swt_3*',
    'org.eclipse.swt.win32.win32.x86_64_*',
    'org.eclipse.ui_3*',
    'org.eclipse.ui.workbench_*',
    'org.eclipse.ui.navigator_*',
    'org.eclipse.egit.ui_*',
    'org.eclipse.egit.core_*',
    'org.eclipse.jgit_*'
)

$jars = New-Object System.Collections.Generic.List[string]
foreach ($pattern in $needed) {
    $found = $null
    foreach ($dir in $searchDirs) {
        $found = Get-ChildItem -Path $dir -Filter $pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -eq '.jar' } | Select-Object -First 1
        if ($found) { break }
    }
    if (-not $found) { throw "Не найден бандл: $pattern" }
    $jars.Add($found.FullName)
}

# Прочие транзитивные зависимости JGit (JavaEWAH, slf4j) нужны для разрешения
# типов при компиляции — добавляем всё, что есть в пуле, чтобы не ловить
# "package does not exist" на редких путях.
foreach ($dir in $searchDirs) {
    Get-ChildItem -Path $dir -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(com\.googlecode\.javaewah|org\.slf4j|JavaEWAH)' } |
        ForEach-Object { if (-not $jars.Contains($_.FullName)) { $jars.Add($_.FullName) } }
}

Write-Host "Бандлов в classpath: $($jars.Count)"

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

$cp = ($jars -join ';')
$sources = Get-ChildItem -Path (Join-Path $bundle 'src') -Filter '*.java' -Recurse |
    Select-Object -ExpandProperty FullName
Write-Host "Исходников: $($sources.Count)"

# В arg-файле javac обратный слэш работает как escape, поэтому все пути
# записываются в прямых слэшах.
function To-JavacPath([string]$p) { $p.Replace('\', '/') }

$argFile = Join-Path $root 'build\javac.args'
$lines = @('-d', "`"$(To-JavacPath $outDir)`"", '-encoding', 'UTF-8', '-proc:none', '-nowarn')
$lines += "-classpath `"$(To-JavacPath $cp)`""
$lines += $sources | ForEach-Object { "`"$(To-JavacPath $_)`"" }
Set-Content -Path $argFile -Value ($lines -join "`n") -Encoding UTF8

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Компиляция не удалась (код $LASTEXITCODE)" }

# Собираем содержимое бандла.
$stage = Join-Path $root 'build\bundle'
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item -Path (Join-Path $outDir '*') -Destination $stage -Recurse -Force
Copy-Item -Path (Join-Path $bundle 'META-INF') -Destination $stage -Recurse -Force
Copy-Item -Path (Join-Path $bundle 'icons') -Destination $stage -Recurse -Force
foreach ($f in @('plugin.xml', 'plugin.properties', 'build.properties')) {
    Copy-Item -Path (Join-Path $bundle $f) -Destination $stage -Force
}
# Свойства сообщений лежат рядом с классом, но попадают в jar из src.
Get-ChildItem -Path (Join-Path $bundle 'src') -Filter '*.properties' -Recurse | ForEach-Object {
    $rel = $_.FullName.Substring((Join-Path $bundle 'src').Length).TrimStart('\')
    $dest = Join-Path $stage $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
}

# Версия бандла берётся из MANIFEST: OSGi кэширует бандл по symbolicName+version,
# поэтому при изменении кода версию нужно поднимать, иначе EDT подхватит старый jar.
$manifestPath = Join-Path $bundle 'META-INF\MANIFEST.MF'
$versionLine = Select-String -Path $manifestPath -Pattern '^Bundle-Version:\s*(.+)$' | Select-Object -First 1
$version = $versionLine.Matches[0].Groups[1].Value.Trim()
Write-Host "Версия бандла: $version"

$jarName = "ru.edt.gitremotedisabler_$version.jar"
$jarPath = Join-Path $distDir $jarName
Push-Location $stage
try {
    & $jar --create --file $jarPath --manifest 'META-INF\MANIFEST.MF' -C . .
    if ($LASTEXITCODE -ne 0) { throw "jar завершился с кодом $LASTEXITCODE" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Готово: $jarPath"
Write-Host ("Размер: {0:N0} байт" -f (Get-Item $jarPath).Length)
