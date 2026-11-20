# Установка/удаление плагина Git Remote Disabler в 1C:EDT через dropins.
#
# dropins — штатный механизм Eclipse: бандл из dropins/plugins подхватывается
# p2-reconciler при старте и переживает обновление EDT (в отличие от правки
# configuration/org.eclipse.equinox.simpleconfigurator/bundles.info).
#
#   .\install.ps1            установить
#   .\install.ps1 -Remove    удалить
#   .\install.ps1 -Restart   установить и перезапустить EDT

param(
    [string]$EdtHome = '',
    [string]$Workspace = '',
    [switch]$Remove,
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'common.ps1')
if (-not $EdtHome) { $EdtHome = Get-DefaultEdtHome }
$built = Get-ChildItem (Join-Path $root 'build\dist') -Filter 'ru.edt.gitremotedisabler_*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName

$dropins = Join-Path $EdtHome 'dropins'
$target = Join-Path $dropins 'plugins'

# 1cedt.exe — только лончер: главное окно принадлежит процессу javaw,
# поэтому искать и закрывать нужно именно его.
function Get-EdtProcess {
    Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -in @('1cedt', 'javaw') -and
        ($_.MainWindowTitle -match '1C:Enterprise Development Tools' -or
         $_.Path -like (Join-Path $EdtHome '*'))
    }
}

function Stop-Edt {
    $procs = @(Get-EdtProcess)
    if ($procs.Count -eq 0) { return $true }
    Write-Host "Закрываю EDT (PID: $($procs.Id -join ', '))..."
    foreach ($p in $procs) { $p.CloseMainWindow() | Out-Null }
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if (@(Get-EdtProcess).Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 500
    }
    Write-Warning "EDT не закрылся за 90 с. Закройте его вручную и повторите."
    return $false
}

function Start-Edt {
    $exe = Join-Path $EdtHome '1cedt.exe'
    if (-not (Test-Path $exe)) { throw "Не найден $exe" }
    if ($Workspace -and (Test-Path $Workspace)) {
        Write-Host "Запускаю EDT на воркспейсе $Workspace..."
        Start-Process -FilePath $exe -ArgumentList @('-data', $Workspace) -WorkingDirectory $EdtHome
    } else {
        Write-Host "Запускаю EDT..."
        Start-Process -FilePath $exe -WorkingDirectory $EdtHome
    }
}

if ($Remove) {
    if (Test-Path $dropins) {
        $files = Get-ChildItem -Path $dropins -Filter 'ru.edt.gitremotedisabler*' -Recurse -File -ErrorAction SilentlyContinue
        if ($files) {
            if (-not (Stop-Edt)) { throw "EDT должен быть закрыт для удаления плагина." }
            foreach ($f in $files) {
                Write-Host "Удаляю $($f.FullName)"
                Remove-Item $f.FullName -Force
            }
            Write-Host "Плагин удалён. Запустите EDT заново."
        } else {
            Write-Host "Плагин не установлен — удалять нечего."
        }
    }
    return
}

if (-not (Test-Path $built)) {
    Write-Host "Сборка..."
    & (Join-Path $root 'build.ps1') | Out-Null
    if (-not (Test-Path $built)) { throw "Сборка не создала $built" }
}

$running = Get-Process -Name '1cedt' -ErrorAction SilentlyContinue
if ($running) {
    if (-not $Restart) {
        throw "EDT запущен (PID: $($running.Id -join ', ')). Закройте его или запустите с -Restart."
    }
    if (-not (Stop-Edt)) { throw "Не удалось закрыть EDT." }
}

New-Item -ItemType Directory -Force -Path $target | Out-Null
# Старые версии бандла убираем, чтобы в dropins не оказалось двух копий.
Get-ChildItem $target -Filter 'ru.edt.gitremotedisabler*' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne (Split-Path $built -Leaf) } |
    ForEach-Object { Write-Host "Удаляю старую версию $($_.Name)"; Remove-Item $_.FullName -Force }
Copy-Item $built (Join-Path $target (Split-Path $built -Leaf)) -Force
Write-Host "Установлено: $(Join-Path $target (Split-Path $built -Leaf))"

# OSGi и реестр расширений кэшируют бандл по symbolicName+version и по пути jar.
# Без сброса этих кэшей EDT продолжает видеть старый plugin.xml, даже когда
# в dropins уже лежит новый jar.
function Clear-EdtCaches {
    $caches = @(
        (Join-Path $EdtHome 'configuration\org.eclipse.osgi'),
        (Join-Path $EdtHome 'configuration\org.eclipse.core.runtime')
    )
    foreach ($c in $caches) {
        if (Test-Path $c) {
            Write-Host "Сбрасываю кэш $([System.IO.Path]::GetFileName($c))..."
            Get-ChildItem $c -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like '.*' } |
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
            Get-ChildItem $c -Force -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^[0-9]+$' } |
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    # Кэш p2.
    $p2 = Join-Path $EdtHome 'configuration\org.eclipse.equinox.p2.core\cache'
    if (Test-Path $p2) {
        Write-Host "Сбрасываю кэш p2..."
        Get-ChildItem $p2 -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like '*.index' } |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

Clear-EdtCaches

if ($Restart) { Start-Edt }
Write-Host ""
Write-Host "Готово. Пункт появится в контекстном меню удалённого репозитория"
Write-Host "в представлении «Git Repositories», прямо над «Удалить удаленный репозиторий»."
