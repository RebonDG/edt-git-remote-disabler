# Общие функции: поиск установленного EDT, JDK и пула бандлов p2.
#
# Все пути определяются автоматически, чтобы скрипты работали на любой машине.
# При необходимости путь задаётся параметром -EdtHome / -JdkHome или
# переменными окружения EDT_HOME / JAVA_HOME.

function Get-DefaultEdtHome {
    $candidates = @()
    if ($env:EDT_HOME) { $candidates += $env:EDT_HOME }

    # Штатные места установки 1C:EDT: менеджер установок и типовые каталоги.
    $startRoot = Join-Path $env:LOCALAPPDATA '1C\1cedtstart\installations'
    if (Test-Path $startRoot) {
        Get-ChildItem $startRoot -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates += (Join-Path $_.FullName '1cedt') }
    }
    $candidates += (Join-Path $env:LOCALAPPDATA '1C\1cedt')

    # Каталоги видов "1C\1CE\components\1c-edt*" и любые подкаталоги 1C
    # в Program Files, где есть plugins/.
    foreach ($base in @(${env:ProgramFiles}, ${env:ProgramFiles(x86)})) {
        if (-not $base) { continue }
        $oneC = Join-Path $base '1C'
        if (-not (Test-Path $oneC)) { continue }
        Get-ChildItem $oneC -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'edt|1cedt' } |
            ForEach-Object { $candidates += $_.FullName }
    }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'plugins'))) { return $c }
    }
    throw "Не найден установленный 1C:EDT. Укажите -EdtHome или переменную EDT_HOME."
}

function Get-DefaultJdkHome {
    $candidates = @()
    if ($env:EDT_JDK_HOME) { $candidates += $env:EDT_JDK_HOME }

    # JDK, поставляемые с EDT (Axiom JDK и подобные), — самый надёжный вариант.
    foreach ($base in @(${env:ProgramFiles}, ${env:ProgramFiles(x86)})) {
        if (-not $base) { continue }
        $oneC = Join-Path $base '1C'
        if (Test-Path $oneC) {
            Get-ChildItem $oneC -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match 'jdk' } |
                ForEach-Object { $candidates += $_.FullName }
        }
        # JBR из Android Studio и JDK от Adoptium.
        $candidates += (Join-Path $base 'Android\Android Studio\jbr')
        $adoptium = Join-Path $base 'Eclipse Adoptium'
        if (Test-Path $adoptium) {
            Get-ChildItem $adoptium -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates += $_.FullName }
        }
    }

    # JAVA_HOME и javac из PATH — в конце: там часто оказывается Java 8,
    # которой Eclipse-бандлы не собираются.
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += (Split-Path (Split-Path $onPath.Source -Parent) -Parent) }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) {
            # Нужен JDK 17+: проверяем по major-версии из release-файла.
            $release = Join-Path $c 'release'
            if (Test-Path $release) {
                $javaVersion = (Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' |
                    Select-Object -First 1).Matches.Groups[1].Value
                if ($javaVersion -and [int]$javaVersion -ge 17) { return $c }
            } else {
                return $c
            }
        }
    }
    throw "Не найден JDK 17+ с javac. Укажите -JdkHome или переменную JAVA_HOME."
}

# Пул бандлов p2: общий кэш бандлов Eclipse (~/.p2) плюс каталог plugins установки.
function Get-BundleSearchDirs {
    param([string]$EdtHome)
    $dirs = @()
    $pool = Join-Path $env:USERPROFILE '.p2\pool\plugins'
    if (Test-Path $pool) { $dirs += $pool }
    if ($EdtHome -and (Test-Path (Join-Path $EdtHome 'plugins'))) {
        $dirs += (Join-Path $EdtHome 'plugins')
    }
    if ($dirs.Count -eq 0) { throw "Не найдены каталоги с бандлами Eclipse (.p2/pool или plugins установки)." }
    return $dirs
}

# Первый подходящий jar по маске из каталогов бандлов.
function Find-BundleJar {
    param(
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string[]]$SearchDirs
    )
    foreach ($dir in $SearchDirs) {
        $f = Get-ChildItem -Path $dir -Filter $Pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -eq '.jar' } | Select-Object -First 1
        if ($f) { return $f.FullName }
    }
    throw "Не найден бандл: $Pattern"
}
