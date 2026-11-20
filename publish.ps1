# Публикация p2 update site в ветку gh-pages.
#
# p2-сайт — это просто каталог со статикой (content.jar, artifacts.jar, plugins/,
# features/), поэтому его хватает выложить на GitHub Pages. Ветка gh-pages
# содержит только содержимое build\site, без исходников.
#
#   .\publish.ps1              собрать сайт и опубликовать
#   .\publish.ps1 -NoBuild     опубликовать уже собранное

param(
    [string]$Remote = 'origin',
    [string]$Branch = 'gh-pages',
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $NoBuild) {
    & (Join-Path $root 'build-site.ps1')
    if ($LASTEXITCODE -ne 0) { throw "build-site.ps1 завершился с ошибкой" }
}

$site = Join-Path $root 'build\site'
if (-not (Test-Path $site)) { throw "Нет каталога $site — сначала соберите сайт" }

# GitHub Pages по умолчанию прогоняет Jekyll, который игнорирует каталоги,
# начинающиеся с подчёркивания, и вообще не нужен для бинарных артефактов.
Set-Content -Path (Join-Path $site '.nojekyll') -Value '' -Encoding ASCII

$tmp = Join-Path $root 'build\gh-pages'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Force $tmp | Out-Null

Push-Location $tmp
try {
    git init -q
    git remote add $Remote (git -C $root remote get-url $Remote)

    # Пробуем взять текущее состояние ветки, чтобы не потерять историю.
    git fetch -q $Remote $Branch 2>$null
    $hasBranch = $LASTEXITCODE -eq 0
    if ($hasBranch) {
        git checkout -q -B $Branch "FETCH_HEAD"
        Get-ChildItem -Force | Where-Object { $_.Name -ne '.git' } | Remove-Item -Recurse -Force
    } else {
        git checkout -q --orphan $Branch
    }

    Copy-Item (Join-Path $site '*') . -Recurse -Force
    git add -A
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm'
    git -c user.name='edt-plugins' -c user.email='edt-plugins@users.noreply.github.com' `
        commit -q -m "Update site $stamp"
    git push -q $Remote $Branch
    if ($LASTEXITCODE -ne 0) { throw "git push не удался" }
    Write-Host "Опубликовано в ветку $Branch"
} finally {
    Pop-Location
}
