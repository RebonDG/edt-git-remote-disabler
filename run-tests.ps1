# Запуск JVM-тестов логики плагина (без EDT и без UI).
#
# Сначала собирает бандл (build.ps1), затем компилирует тест против готовых
# классов и запускает его. Тест работает с настоящими git-репозиториями во
# временном каталоге, поэтому проверяет ровно те операции, которые выполняет
# плагин.

param(
    [string]$JdkHome = '',
    [string]$EdtHome = '',
    [string]$MainClass = 'ru.edt.gitremotedisabler.tests.RemoteToggleServiceTest',
    [string[]]$TestArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'common.ps1')
if (-not $JdkHome) { $JdkHome = Get-DefaultJdkHome }
if (-not $EdtHome) { $EdtHome = Get-DefaultEdtHome }
$searchDirs = Get-BundleSearchDirs -EdtHome $EdtHome

$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path $javac)) { throw "Не найден javac: $JdkHome" }

# 1. Сборка бандла.
& (Join-Path $root 'build.ps1') -JdkHome $JdkHome -EdtHome $EdtHome | Out-Null
$bundleClasses = Join-Path $root 'build\classes'
if (-not (Test-Path $bundleClasses)) { throw "Нет классов бандла: $bundleClasses" }

function Find-Jar([string]$pattern) {
    return Find-BundleJar -Pattern $pattern -SearchDirs $searchDirs
}

$cp = @(
    (Find-Jar 'org.eclipse.jgit_*.jar'),
    (Find-Jar 'org.eclipse.jface_*'),
    (Find-Jar 'org.eclipse.swt_3*'),
    (Find-Jar 'org.eclipse.swt.win32.win32.x86_64_*'),
    (Find-Jar 'org.eclipse.core.commands_*'),
    (Find-Jar 'org.eclipse.core.jobs_*'),
    (Find-Jar 'org.eclipse.core.expressions_*'),
    (Find-Jar 'org.eclipse.core.runtime_*'),
    (Find-Jar 'org.eclipse.equinox.common_*'),
    (Find-Jar 'org.eclipse.equinox.registry_*'),
    (Find-Jar 'org.eclipse.osgi_*'),
    (Find-Jar 'org.eclipse.ui_3*'),
    (Find-Jar 'org.eclipse.ui.workbench_*'),
    (Find-Jar 'org.eclipse.ui.navigator_*'),
    (Find-Jar 'org.eclipse.egit.ui_*'),
    (Find-Jar 'org.eclipse.egit.core_*'),
    (Find-Jar 'com.googlecode.javaewah.JavaEWAH_*.jar'),
    (Find-Jar 'org.slf4j.api_1.7.30.v2022*.jar')
)

$testOut = Join-Path $root 'build\test-classes'
if (Test-Path $testOut) { Remove-Item $testOut -Recurse -Force }
New-Item -ItemType Directory -Force -Path $testOut | Out-Null

function To-P([string]$p) { $p.Replace('\', '/') }

$sources = Get-ChildItem (Join-Path $root 'tests\src') -Filter '*.java' -Recurse |
    Select-Object -ExpandProperty FullName
$fullCp = (@($bundleClasses) + $cp) -join ';'

$argFile = Join-Path $root 'build\test-javac.args'
$lines = @('-d', "`"$(To-P $testOut)`"", '-encoding', 'UTF-8', '-proc:none', '-nowarn')
$lines += "-classpath `"$(To-P $fullCp)`""
$lines += $sources | ForEach-Object { "`"$(To-P $_)`"" }
Set-Content -Path $argFile -Value ($lines -join "`n") -Encoding UTF8

Write-Host "Компиляция теста ($($sources.Count) файлов)..."
& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Компиляция теста не удалась (код $LASTEXITCODE)" }

Write-Host "Запуск $MainClass..."
& $java -cp (@($testOut, $bundleClasses) + $cp -join ';') $MainClass @TestArgs
exit $LASTEXITCODE
