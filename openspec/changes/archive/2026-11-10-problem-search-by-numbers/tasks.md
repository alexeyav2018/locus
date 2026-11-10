# Tasks

Документы `openspec/context/`, которые изменение затрагивает: журнал
решений (ADR-0064 новая, ADR-0031 заменена — сделано в propose),
`scenarios.md` (С4 — подбор по номерам, ссылка на ADR-0064),
`glossary.md` (строка `MatchMode` — ссылка, если ведёт на ADR-0031).
`agent/CLAUDE.md` — строка о `find_problems`. `CLAUDE.md` — раздел
«Статус» и сводка «Ключевые решения» при архивации.
`domain-model.md`, `architecture.md`, `strategy.md`, `standards.md`,
`antipatterns.md`, `ux-concept.md` — не затрагиваются.

## 1. Разбор номеров, отбор и порядок

- [x] 1.1 `ProblemNumbers` (`ru.locus.problem`): `parse(String)` —
  разделители `[\s,;]+`, кусок только из цифр и в пределах `long`,
  иначе отказ «Не разобрано: «…»» со всеми неразобранными кусками;
  `of(List<Long>)`; повтор снимается; пустой — отказ «Не задано ни одного
  номера»; `ids()` — `List<ProblemId>`. Модульный тест
  `ProblemNumbersTest`: «12, 3;7» с переводом строки, «3 3 3», «3, пять»,
  «3-7», пустая строка и пробелы, переполнение `long`, `of(List.of())`.
  Проверка: `mvn test -Dtest=ProblemNumbersTest`.
- [x] 1.2 `ProblemRepository`: `order by p.id desc` в `findByTopic`,
  `findByIds`, `search`; javadoc — «по убыванию номера». Проверить,
  что `ProblemService.problems(ids)` и перестройка (`ProblemService`,
  вызов `findByTopic` около `:624`) от порядка не зависят.
- [x] 1.3 `ProblemService.byNumbers(ProblemNumbers)` → запись
  `NumberedProblems(found, missing)`: `findByIds`, `named`, ненайденные
  по убыванию; без `@PreAuthorize`, без владельца; javadoc —
  ADR-0064 и отличие от `problems(ids)`.
- [x] 1.4 Тесты сервиса и репозитория: `ProblemSearchTest` —
  `byNumbers` (порядок 12, 7, 3; ненайденный назван; повтор);
  поиск по разметке по убыванию. Поправить проверки, опиравшиеся
  на возрастание, в `ProblemSearchTest`, `ProblemSearchRepositoryTest`
  и прочих (`grep -rn "containsExactly" src/test` по местам с номерами
  Задач поиска и списка Темы). Проверка:
  `mvn test -Dtest='ProblemSearch*Test,ProblemNumbersTest'`.

## 2. Экран поиска

- [x] 2.1 `ProblemSearchController.search`: параметр `numbers`;
  есть — `ProblemNumbers.parse` и `byNumbers`, `filter` =
  `ProblemFilter.empty()`, в модель `numbers` (текст) и `missing`;
  нет — прежняя ветка. Отказ разбора — `error`. Javadoc — ADR-0064.
- [x] 2.2 `templates/problem/search.html`: левая колонка —
  `<div class="sticky">` с формой «Номера Задач» (`textarea
  name="numbers"`, кнопка «Поиск», скрытый `from`) над прежней формой;
  строка «Нет в библиотеке: …» (`alert`) над «Найдено Задач»;
  комментарии шаблона — ADR-0064. CSS — только если `.filters.sticky`
  перестанет подходить, в `locus.css`.
- [x] 2.3 `ProblemSearchScreenTest`: `?numbers=12, 3;7` — Задачи
  12, 7, 3 в этом порядке, поле заполнено, форма разметки без выбранного;
  с `node` в том же адресе — те же три; несуществующий номер —
  «Нет в библиотеке: …»; `?numbers=3, пять` — отказ с «пять»;
  `?numbers=` — отказ «Не задано ни одного номера»; поиск по условиям
  по убыванию. Список Темы по убыванию — в экранном тесте дерева
  (найти по `findByTopic`/списку Темы). Проверка:
  `mvn test -Dtest='ProblemSearchScreenTest'` и найденный тест дерева.

## 3. Помощник

- [x] 3.1 `ProblemTools.findProblems`: свойство `numbers` (массив целых),
  при нём — `ProblemNumbers.of` и `byNumbers`; ответ всегда объект
  `{problems, missing}`; описание инструмента — оба способа и что номера
  отменяют условия.
- [x] 3.2 `LibraryToolsTest`: прежние проверки читают `problems`
  из объекта; новая — сценарий «Поиск по номерам» (12, 7, 3 и `missing`
  [999] при узле); пустой `numbers` — отказ. `EveryToolAnswersTheClientTest`
  и `AssistantIsFilteredByOwnerTest` — поправить, если разбирают ответ
  `find_problems`. Проверка: `mvn test -Dtest='LibraryToolsTest,EveryToolAnswersTheClientTest,AssistantIsFilteredByOwnerTest'`.
- [x] 3.3 `agent/CLAUDE.md`: строка о `find_problems` — «по разметке
  или по списку номеров».

## 4. Документы, версия, приёмка

- [x] 4.1 `scenarios.md` С4 — подбор по номерам, ссылка ADR-0064 вместо
  ADR-0031; `glossary.md` — ссылки на ADR-0031 заменить на ADR-0064.
  Проверка: `grep -rn "0031-uslovija" openspec/context openspec/specs
  CLAUDE.md` — остались только исторические упоминания.
- [x] 4.2 Приёмка: `mvn clean package` зелёная;
  `python3 openspec/context/adr/build_index.py --check` проходит;
  `npx -y @fission-ai/openspec@latest validate problem-search-by-numbers`
  проходит.
- [x] 4.3 Версия: `pom.xml` 3.20.1 → 3.21.0; в `CHANGELOG.md` сверху
  запись `## 3.21.0 — <дата>` со строкой из proposal.md.

## Workflow follow-up

- Архивация: ADR-0064 «Предложено» → «Принято», `build_index.py`,
  `CLAUDE.md` («Статус», «Ключевые решения» — ADR-0064), строка
  бэклога ✅, тег `v3.21.0`.
