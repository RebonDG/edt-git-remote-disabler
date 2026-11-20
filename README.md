# Git Remote Disabler — плагин для 1C:EDT

Добавляет в дерево Git-репозиториев (представление **Git Repositories**) пункт,
который **отключает** удалённый репозиторий, не удаляя его, и включает обратно.

Пункт встаёт в контекстном меню узла удалённого репозитория прямо над
**«Удалить удаленный репозиторий»**. Его подпись меняется по состоянию:

| Состояние remote | Пункт меню |
|---|---|
| включён | **Отключить удаленный репозиторий** |
| отключён | **Включить удаленный репозиторий** |

## Что именно делает «Отключить»

Ровно то, что делают руками эти две команды:

```bash
git branch -rd $(git branch -r | grep '^ *origin/')   # удалить скачанные ветки
git config --unset-all remote.origin.fetch            # отключить fetch
```

Плюс две вещи, без которых отключение было бы неполным:

- `remote.origin.skipDefaultUpdate = true` — чтобы `git fetch --all` и
  `git remote update` пропускали этот remote целиком, а не дёргали его;
- исходные значения сохраняются, чтобы включение вернуло всё как было:
  `remote.origin.disabledfetch` (refspec-и) и `remote.origin.disabledskipdefaultupdate`.

Секция `remote.origin` и её `url` **не трогаются** — поэтому узел остаётся в дереве
EGit, и его можно включить обратно. В этом отличие от «Удалить удаленный
репозиторий», которая стирает секцию целиком.

«Включить» возвращает refspec-и fetch, снимает маркеры и восстанавливает
исходное `skipDefaultUpdate`. Сами ветки появятся при следующем fetch.

## Установка

### Из update site (рекомендуется)

1. **Справка → Установить новое ПО…**
2. **Добавить…** → в поле *Расположение* вставить:

   ```
   https://rebondg.github.io/edt-git-remote-disabler/
   ```

3. Отметить **Git Remote Disabler** → **Далее** → принять лицензию → **Готово**.
4. Перезапустить EDT.

Сайт собирается в ветку `gh-pages` скриптом `publish.ps1`.

### Локально, из папки (для разработки)

```powershell
.\install.ps1            # установить в dropins (EDT должен быть закрыт)
.\install.ps1 -Restart   # установить и запустить EDT на воркспейсе проекта
.\install.ps1 -Remove    # удалить
```

Плагин ставится в `dropins/plugins` установленного EDT — это штатный механизм
Eclipse, он переживает обновления EDT и не требует правки `bundles.info`.

**Важно про кэш.** OSGi кэширует бандл по `Bundle-SymbolicName + Bundle-Version`,
а Eclipse — по кэшу реестра расширений в `configuration/org.eclipse.core.runtime`.
Поэтому после пересборки нужно **поднять `Bundle-Version`** в
`bundle/META-INF/MANIFEST.MF`, а `install.ps1` сам сбрасывает оба кэша.
Без этого EDT продолжает показывать старый `plugin.xml`, даже когда в `dropins`
уже лежит новый jar — именно из-за этого первая установка показала два пункта
меню вместо одного.

Если EDT установлен в другое место, путь передаётся параметром:

```powershell
.\install.ps1 -EdtHome 'D:\EDT\1cedt'
```

## Сборка и тесты

```powershell
.\build.ps1      # javac против бандлов установленного EDT → build\dist\*.jar
.\run-tests.ps1  # тесты логики на настоящих git-репозиториях
```

Сборка намеренно не использует Maven/Tycho: для одного бандла это лишние
минуты, а компиляция идёт напрямую против бандлов EDT из `plugins/` и
`~/.p2/pool/plugins`.

Проверки, которые реально выполняются:

- `RemoteToggleServiceTest` — 6 тестов на временных git-репозиториях:
  удаление веток, сохранение `url` и секции, восстановление конфигурации,
  идемпотентность, сохранение исходного `skipDefaultUpdate`.
- `BundleResolveTest` — состав jar, содержимое `plugin.xml`, загрузка классов
  и их предки (`AbstractHandler`, `IElementUpdater`).
- `LiveRepositoryRun` — прогон на копии настоящего рабочего репозитория:

  ```powershell
  .\run-tests.ps1 -MainClass ru.edt.gitremotedisabler.tests.LiveRepositoryRun `
                  -TestArgs '<путь-к-копии-репозитория>','origin'
  ```

Тесты UI (само контекстное меню) автоматизировать на этой машине нельзя —
проверка пункта меню выполняется вручную в запущенном EDT.

## Поведение, о котором стоит знать

**Fetch отключённого remote.** Git CLI (`git fetch origin`) при пустых refspec-ах
завершается успешно и печатает `branch HEAD -> FETCH_HEAD`. JGit, на котором
работает EGit, в той же ситуации бросает `TransportException: Nothing to fetch.`
Поэтому нажатие «Получить (fetch)» на отключённом репозитории покажет ошибку.

Ветки при этом не возвращаются — это и есть цель. `git fetch --all` и
`git remote update` отключённый remote пропускают благодаря `skipDefaultUpdate`;
JGit-овский «fetch этого remote» просто нечего тянуть.

**Восстановление вручную.** Отключение полностью обратимо и без плагина:

```bash
git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'
git config --unset remote.origin.disabled
git config --unset remote.origin.disabledfetch
git config --unset remote.origin.skipDefaultUpdate
git config --unset remote.origin.disabledskipdefaultupdate
```

## Требования

- 1C:EDT 2026.2 (Eclipse 4.x, EGit/JGit 6.8.14).
- Java 21+ для сборки (используется Axiom JDK 25 или JBR из Android Studio).

Плагин — OSGi-бандл на расширениях `org.eclipse.ui.commands` /
`org.eclipse.ui.menus` / `org.eclipse.ui.handlers`. Это существенно: EDT
построен на Eclipse RCP и EGit, а не на IntelliJ/JetBrains-платформе, поэтому
плагин для IntelliJ IDEA здесь не загрузился бы.

## Структура

```
bundle/                     OSGi-бандл
  META-INF/MANIFEST.MF      Require-Bundle: egit.ui, egit.core, jgit
  plugin.xml                команда, обработчик, пункт меню
  build.properties
  icons/obj16/              иконки состояний
  src/ru/edt/gitremotedisabler/
    RemoteToggleService.java  логика (только JGit, без UI)
    ToggleRemoteHandler.java  действие + подпись пункта по состоянию
    Activator.java            логирование, иконки
    Messages.java             NLS
    messages*.properties      строки (ru, en)
tests/src/                  тесты логики и целостности бандла
build.ps1  run-tests.ps1  install.ps1
```
