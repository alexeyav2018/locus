# Tasks

Документы контекста: правятся `openspec/context/glossary.md` (термины
«Разминка» `Warmup` и «Итог Разминки» `WarmupOutcome` в личный контур,
значения `SUCCEEDED`/`FAILED` в «Перечисления»), `domain-model.md`
(граница общего и личного, схема связей, абзац построенного, операции
«Удаление Ученика», «Простановка отметки», новая «Удаление Разминки»),
`scenarios.md` (сценарий разминки на занятии), `ux-concept.md`
(Разминки в составе Кабинета) и `CLAUDE.md` (абзац статуса `warmup`,
счёт возможностей в `openspec/specs/`, строка таблицы сценариев не
меняется, сводка ключевых решений — ADR-0061). `strategy.md`,
`architecture.md`, `standards.md`, `antipatterns.md` не затрагиваются:
стратегия заменяется только целиком (design.md, решение 8), новых
технических правил и граблей нет.

## 1. Схема, запись, репозиторий

- [x] 1.1 Миграция `0018-warmup.yaml` (четыре таблицы, ключи и каскады — design.md, решение 1;
  шапка-комментарий по образцу `0007-assignments.yaml`); `db.changelog-master.yaml` подхватывает
  папку целиком (`includeAll`), правка не нужна;
  проверка — `mvn test -Dtest=MigrationsOnStartupTest`
- [x] 1.2 Пакет `ru.locus.warmup`: `WarmupId`, `Warmup` (владелец, дата, имя Группы или `null`,
  участники и Задачи — непустые, без повторов), `WarmupOutcome` (`SUCCEEDED`, `FAILED`, подпись
  словом), `WarmupNotFoundException` (404); проверка — `get_diagnostics_for_file`
- [x] 1.3 `WarmupRepository` на `JdbcClient`: `create(owner, heldOn, groupName, students, problems)`,
  `findById(owner, id)`, `find(owner, student)` (новые по дате первыми, `student` — `null` или
  участник), `delete(owner, id)`, `findOutcomes(owner, id)`, `putOutcome`/`removeOutcome(owner, id,
  student, problem, …)`, `countByStudent(owner, student)`, `countOutcomesByPairs(owner, student)`
  (пока возвращает карту по `ru.locus.work.ProblemPair`/`Solved`; переезд — раздел 2)
  и единственный без владельца `countByProblem(problem)` (класс ADR-0036); проверка —
  `get_diagnostics_for_file`
- [x] 1.4 `WarmupRepositoryTest`: создание и чтение с порядком Задач, отбор по участнику,
  удаление уносит итоги, пустой клетки нет (удаление строки), чужой Ученик в участниках
  и итог не участнику падают на ключах, счёт пар; `OwnerIsRequiredByWarmupsTest` — у каждого
  публичного метода `UserId`, кроме поимённо `countByProblem`; проверка —
  `mvn test -Dtest='WarmupRepositoryTest,OwnerIsRequiredByWarmupsTest'`

## 2. Справка из двух источников

- [x] 2.1 `Solved` переносится в `ru.locus.mastery` (javadoc — о Работах и Разминках),
  `ProblemPair` заменяется на `mastery.Cell` и снимается (`safe_delete_symbol`);
  `StudentWorkRepository.countCheckedByPairs` и `WarmupRepository.countOutcomesByPairs` отдают
  `Map<Cell, Solved>` (design.md, решение 5); `StudentWorkRepositoryTest` — по новым типам;
  проверка — `get_diagnostics_for_file` по затронутым файлам
- [x] 2.2 `MasteryCell`: вместо `solved, checked` — `Solved works, Solved warmups`; `hint()` —
  «Работы: N из M · разминки: K из L»; `MasteryService.cellsOf` читает обе карты (зависимость
  от `WarmupRepository`, javadoc класса); комментарий фрагмента `mastery-cells.html` — о новой
  справке; `MasteryCellTest`, `MasteryServiceTest`, `MasteryScreenTest` — новые подписи и случай
  «Работы: 1 из 1 · разминки: 1 из 2»; проверка —
  `mvn test -Dtest='MasteryCellTest,MasteryServiceTest,MasteryScreenTest,StudentWorkRepositoryTest'`

## 3. Сервис Разминок и Владение по Разминке

- [x] 3.1 `Addressee.of` — публичная, javadoc типа — «адресат выдачи или Разминки» (design.md,
  решение 3); проверка — `get_diagnostics_for_file`
- [x] 3.2 `WarmupService`: `hold(Addressee, heldOn, problems)` (проверки до записи — design.md,
  решение 3), `warmup(id)`, `list(student)`, `problemsOf`, `outcomesOf`, `delete(id)`; всё —
  `hasRole('TEACHER')`, владелец из `CurrentUser`; запись `Outcome` с `fromForm` (решение 7);
  проверка — `get_diagnostics_for_file`
- [x] 3.3 `MasteryService.cellsOfWarmup(WarmupId)` (объединение пар, порядок — решение 4)
  и `markByWarmup(WarmupId, StudentId, List<Mark>)` (своя Разминка, участник, пара из Задач
  Разминки, все проверки до записи); `WarmupService.record(id, student, outcomes, marks)` —
  одна транзакция: проверки итогов → `markByWarmup` → запись итогов; проверка —
  `get_diagnostics_for_file`
- [x] 3.4 Ответчики `WarmupsOfProblem implements ProblemUsage` («вошла в Разминки (N)»)
  и `WarmupsOfStudent implements StudentUsage` («участвовал в Разминках (N)») — только
  репозиторий и `CurrentUser`; javadoc `ProblemUsage` — о Разминках; `ProblemUsageDebtTest`
  и `StudentUsageDebtTest` — пятый и очередной ответчик названы; `WarmupsOfProblemTest`,
  `WarmupsOfStudentTest`; проверка — `mvn test -Dtest='*UsageDebtTest,WarmupsOf*Test'`
- [x] 3.5 `WarmupServiceTest`: Разминка Ученику и Группе (участники — только действующие,
  имя Группы текстом и после переименования), выбывший прямо и Группа без действующих —
  отказ, без Задач и повтор Задачи; итоги ставятся и пустая клетка не хранится; итог
  не участнику — отказ; «справился» отметку не ставит; отметка по Разминке без Работы
  пишется; пара не из Задач Разминки — ни итога, ни отметки; удаление Разминки оставляет
  отметки; Задача в Разминке заморожена и оттаивает после удаления; Ученик с Разминкой
  не удаляется, Группа удаляется; справка показывает Работы и Разминки порознь;
  `WarmupsAreFilteredByOwnerTest` — чужая Разминка неотличима от несуществующей на каждой
  операции, чужой адресат, списки двух Учителей, Администратор без роли Учителя; проверка —
  `mvn test -Dtest='Warmup*Test,Mastery*Test'`

## 4. Экраны

- [x] 4.1 `Addresses.WARMUPS`; `WarmupController`: `GET /warmups` (необязательный `student`),
  `GET /warmups/new`, `POST /warmups`, `GET /warmups/{id}`, `POST /warmups/{id}/students/{studentId}`
  (списки сырыми, отказ — сообщение, `EnteredOutcomes` и раскрытый блок участника),
  `POST /warmups/{id}/deletion`; возврат `from` по ADR-0060; `ShellAdvice` знает раздел
  `warmups`; проверка — `WarmupControllerIsThinTest` по образцу соседних зелёный
- [x] 4.2 Шаблоны `warmup/list.html`, `warmup/form.html`, `warmup/warmup.html` (design.md,
  решение 6; фрагмент `mastery-cells` с `EnteredOutcomes`; без своих `<style>` и скриптов);
  в `problem/search.html` — «Провести Разминку» с `formaction`; в шапке — «Разминки» после
  «Задания», только Учителю; на карточке Ученика — плитка «Разминки Ученика»; проверка —
  страницы открываются (`mvn test -Dtest=WarmupScreenTest`)
- [x] 4.3 `WarmupScreenTest`: из поиска — форма с отмеченными Задачами и сегодняшней датой;
  заведение ведёт на страницу; итоги словами на виду и пустая клетка «—»; сохранение итогов
  и отметки одним действием; отказ по паре — сообщение, раскрыт блок участника, введённое
  выбрано; удаление — на список; пустой список говорит, что Разминка начинается с поиска;
  чужая Разминка — 404, посетитель без входа — на вход; проверка —
  `mvn test -Dtest='WarmupScreenTest,EveryPageShowsTheVersionTest'`

## 5. Документы, приёмка, версия

- [ ] 5.1 `glossary.md`, `domain-model.md`, `scenarios.md`, `ux-concept.md` — как перечислено
  в начале файла; `CLAUDE.md` — абзац статуса `warmup` после `intake-answer-links`, заголовок
  «Статус» дополнить разминкой, «двадцать одна возможность» → «двадцать две» с `warmups`,
  строка ADR-0061 в «Ключевых решениях»; проверка — ссылки целы, термины совпадают со словарём
- [ ] 5.2 Приёмка: `mvn clean package` зелёный
- [ ] 5.3 Поднять версию в `pom.xml` до `3.18.0` и дописать сверху `CHANGELOG.md` строку
  из proposal.md, раздел «Версия»; проверка — `grep -n 3.18.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive warmup`: ADR-0061 — «Принято», строка бэклога — «построено» (признак
  готовности включает сеанс учителя на стенде), коммит архивации, squash в `develop`,
  тег `v3.18.0` на коммит слияния и в `origin`.
