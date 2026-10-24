# Tasks

Документы `openspec/context/`, которые изменение затрагивает:
`glossary.md` (Черновик сборки — держится Черновиком Задачи; Черновик
Задачи — построен, сомнительное место пометкой `⟦…⟧`, страница
вычитки), `domain-model.md` (Черновик Задачи построен: строки
по Черновикам сборки, держание, срок, перезапуск),
`architecture.md` (рабочая папка — держание и старт; инструменты
Черновика Задачи), журнал решений — ADR-0059 (Предложено → Принято
при архивации), `ux-concept.md`, `antipatterns.md`, `scenarios.md`,
`strategy.md`, `standards.md` — не затрагивают. `openspec/backlog.md`:
строка `problem-intake` — ✅ при архивации; карточка `intake-skill` —
имена инструментов (`create_problem_draft`, `get_problem_draft`,
`update_problem_draft`, `save_problem_draft`, `create_method`)
вместо рабочих. `CLAUDE.md` — статус при архивации.

## 1. Схема, запись и репозиторий

- [x] 1.1 Миграция `0017-problem-intake.yaml` (подключить в
      `db.changelog-master`): `problem_draft` с `uq (id, user_id)`,
      `problem_draft_line` с составными ключами на `problem_draft`
      и `assembly_draft` (у `assembly_draft` добавить `uq (id, user_id)`,
      если его нет), `problem_draft_topic`, `problem_draft_method`,
      `problem_draft_characteristic` с каскадом от библиотеки,
      `problem_draft_note`; комментарии в духе `0011` (design.md Р1);
      проверка — `MigrationsOnStartupTest` зелёный
- [x] 1.2 Записи `ProblemDraftId`, `ProblemDraft` (поля Р1, `Editor`
      `OWNER`/`ASSISTANT`, `Note.Kind`), `ProblemDraftContent`
      (содержимое для заведения) и `ProblemDraftChange` (необязательные
      поля, `null` — не трогать); модульный тест `ProblemDraftTest` —
      копии списков, `ProblemDraftChange.applyTo` меняет только
      названное
- [x] 1.3 `ProblemDraftRepository` (JDBC, как `AssemblyDraftRepository`):
      `create(UserId, content, editor, now)`, `find(UserId, id)`,
      `findByOwner(UserId)`, `write(UserId, id, version, content,
      editor, now)` — `UPDATE … AND version = ?`, ноль строк — пусто;
      `delete(UserId, id, version)`, `deleteChangedBefore(Instant)`,
      `deleteAll()`; `ProblemDraftRepositoryTest` — запись и чтение
      всех частей в порядке `position`, версия растёт, старая версия
      не пишет
- [x] 1.4 `OwnerIsRequiredByProblemDraftsTest` — `UserId` у каждого
      метода, кроме `deleteChangedBefore` и `deleteAll` поимённо;
      база не даёт строке сослаться на черновик сборки другого
      владельца (составной ключ)
- [x] 1.5 `mvn test -Dtest='ProblemDraft*Test,OwnerIsRequiredByProblemDraftsTest,MigrationsOnStartupTest'` зелёный

## 2. Держание Черновиков сборки и уборка

- [x] 2.1 `AssemblyDraftRepository.deleteByIds` и `deleteCreatedBefore`:
      `NOT EXISTS` по `problem_draft_line` (design.md Р4);
      `AssemblyDraftRepositoryTest` — держимый не удаляется ни тем,
      ни другим, свободный — удаляется
- [x] 2.2 `AssemblyDraftService.check(PdfAssemblyOrder)` — проверки
      `assemble` без сборки, те же тексты отказов; `assemble` зовёт
      её же; `AssemblyDraftServiceTest` — чужой, вне страниц, Рамка
      на диапазоне
- [x] 2.3 Настройка `locus.problem.intake.ttl` (по умолчанию `7d`)
      в `application.yaml` и записи свойств; старт —
      `AssemblyDraftConfiguration` зовёт `ProblemDraftService.clearAll()`
      (заводится в 3.1 — здесь заготовка метода с удалением всех
      черновиков Задачи и вызовом `AssemblyDraftService.clearAll()`)
- [x] 2.4 Тест на `TestClock`: Черновик сборки старше срока под живым
      Черновиком Задачи остаётся при `sweep`; Черновик Задачи старше
      своего срока от последней правки уходит, и тогда же — его
      Черновик сборки; правленый недавно — остаётся; `clearAll` —
      ни одного из обоих
- [x] 2.5 `mvn test -Dtest='AssemblyDraft*Test,DraftsAreFilteredByOwnerTest,OwnerIsRequiredByDraftsTest'` зелёный

## 3. Сервис Черновика Задачи

- [ ] 3.1 `DoubtMarks`: `strip`, `fragments` с контекстом (design.md
      Р5); `DoubtMarksTest` — внутри формулы, несколько пометок,
      непарный знак
- [ ] 3.2 `ProblemService.requireDraftMarkup(topics, methods,
      characteristics)` — пакетный, поверх `requireExistingMarkup`
      и проверки листа (Р3); пустые списки допустимы
- [ ] 3.3 `ProblemDraftService` (все методы `hasRole('ADMINISTRATOR')`):
      `create(content, editor)` с уборкой, `drafts()` с уборкой,
      `draft(id)`, `write(id, version, change, editor)`,
      `save(id, version, Kind TEXT|SNAPSHOT)` — порядок Р4 (удалить
      строку черновика, затем `ProblemService.create` со снятыми
      пометками), `delete(id, version)`, `sweep()`, `clearAll()`;
      `ProblemDraftNotFoundException`, `ProblemDraftChangedException`
      с текстом «перечитайте»; `evidence(draft)` и `rendering(draft)`
      для страницы — картинки `data:` (`TextPreview` и сборка строк
      слота), отказ отрисовки на месте
- [ ] 3.4 `ProblemDraftServiceTest`: запись проверяет Тему-лист, Метод,
      строки; старая версия — отказ, черновик прежний; `save` TEXT —
      Задача с текстом без пометок и Уликами, черновика нет; `save`
      SNAPSHOT — Задача без текста; отказ `create` (нет Метода) —
      черновик на месте; два черновика по одному фото — оба
      сохраняются Задачами, после второго Черновика сборки нет;
      Учитель без роли Администратора — отказ
- [ ] 3.5 `ProblemDraftsAreFilteredByOwnerTest`: Б не читает,
      не пишет, не сохраняет, не удаляет черновик А — отказ тот же,
      что на несуществующий; строка по черновику сборки А в черновике
      Б — отказ сервиса
- [ ] 3.6 `mvn test -Dtest='ProblemDraft*Test,DoubtMarksTest,ProblemServiceTest'` зелёный

## 4. Страница вычитки

- [ ] 4.1 Разбор строк формы вынести из `ProblemController.order`
      в пакетную `AssemblyOrderForm` (вместе с `frameOrNull`,
      `requireFrames`, `page`); `ProblemController` зовёт её;
      `ProblemScreenTest` и `AssemblyScreenTest` зелёные
- [ ] 4.2 `Addresses.PROBLEM_INTAKE`; `ProblemDraftController`:
      адреса design.md Р6 — список с уборкой, страница (чужой — 404),
      «Сохранить черновик», «Сохранить как Задачу», «Взять снимок
      как есть» (сначала запись введённого, затем заведение отдельным
      вызовом), «Удалить черновик»; отказ версии — хранимый черновик
      с отказом; прочие отказы — хранимый с отказом; контроллер тонкий
      (добавить в `ProblemControllerIsThinTest` или его образец)
- [ ] 4.3 `templates/problem/intake.html` и `intake-list.html`: метка
      `scope--personal`; Улика и Отрисовка двумя колонками;
      «Сомнительные места» с `<mark>` и причинами; поля текстов;
      поле файла и строки `problem/assembly :: row` по слотам; Часть,
      Темы, Методы, Характеристики теми же элементами, что у формы
      Задачи; новые Методы со ссылкой на словари; «изменён Помощником /
      вами» и время; скрытая `version`; три кнопки `formaction`;
      удаление под `<details>`; `from` и «← назад»
- [ ] 4.4 `locus.css`: две колонки на широком, столбец на планшете,
      подсветка сомнительного; проверить на 390 px и 1024 px;
      ссылка «Черновики Задач: N» на странице Помощника у
      Администратора (`AssistantController` спрашивает
      `ProblemDraftService` через вопрос в `ru.locus.assistant`,
      как `TurnAttachments`, — без зависимости от области Задач)
- [ ] 4.5 `ProblemIntakeScreenTest` — признак готовности карточки:
      черновик с текстом и Рамками по двум фото показывает Улику
      рядом с отрисовкой; подсветка `⟦8⟧`; сохранение со старой
      версией — отказ «перечитайте», с текущей — Задача с текстом
      и Уликой и переход на неё; «взять как есть» — Задача без
      текста; отказ без Метода — правка текста сохранена; чужой — 404;
      Учитель без роли Администратора — 403; по второму черновику
      с того же фото — вторая Задача; отметка «изменён вами»
- [ ] 4.6 `mvn test -Dtest='ProblemIntakeScreenTest,ProblemScreenTest,AssemblyScreenTest,EveryPageShowsTheVersionTest,ProblemControllerIsThinTest'` зелёный

## 5. Инструменты Помощника

- [ ] 5.1 `ProblemDraftTools`: шесть инструментов design.md Р7,
      ответ — плоский JSON `snake_case`, отказ — `ToolResult.refusal`
      с текстом сервиса; `ProblemTools.line` — пакетный для общего
      разбора строк; `editor` — `ASSISTANT`
- [ ] 5.2 `DictionaryTools.create_method` — обёртка
      `SolutionMethodService.create`
- [ ] 5.3 `ToolsAreThinTest`: `ProblemDraftTools` в `TOOLS`,
      `ProblemDraftRepository` в `FORBIDDEN`; тест реестра — ровно
      семнадцать имён спеки; `AgentSkillsNameRegisteredToolsTest`
      зелёный
- [ ] 5.4 Тесты по HTTP клиентом SDK (образец
      `EveryToolAnswersTheClientTest`): заведение и чтение; правка
      по старой версии после правки на странице — отказ, черновик
      учителя; правка одной части; чужой и несуществующий — один
      отказ; `save_problem_draft` — номер Задачи; строка по чужому
      черновику сборки — отказ; `create_method` — новый и повтор
      в другом регистре; правка инструментом — «изменён Помощником»
      на странице
- [ ] 5.5 `mvn test -Dtest='*Tools*Test,EveryToolAnswersTheClientTest,ToolRegistryTest'` зелёный

## 6. Документы, бэклог и версия

- [ ] 6.1 `glossary.md`: Черновик сборки — держится живым Черновиком
      Задачи; Черновик Задачи — построен, срок, перезапуск;
      сомнительное место — пометка `⟦…⟧`; `domain-model.md` —
      Черновик Задачи построен; `architecture.md` — держание
      и старт рабочей папки, инструменты Черновика Задачи
- [ ] 6.2 `openspec/backlog.md`, карточка `intake-skill`: имена
      инструментов из реестра
- [ ] 6.3 `mvn clean package` зелёный
- [ ] 6.4 Поднять версию в `pom.xml` до 3.7.0 и дописать в `CHANGELOG.md`
      строку из proposal.md, раздел «Версия»

## Workflow follow-up

- Архивация: ADR-0059 — «Принято» (владелец видел на гейте),
  `build_index.py`, строка бэклога ✅, `CLAUDE.md` — статус.
