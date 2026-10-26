# Tasks

Документы контекста: правится `openspec/context/domain-model.md` — строка
«Простановка отметки» (раздел правил операций) дополняется тем, что отметки
ставятся вместе с приёмом Работы или её вердиктом одним действием, а приём
и отметки — одна транзакция; и `CLAUDE.md` — в абзаце `mastery-marks`
устаревший `POST /mastery` заменяется отсылкой к `work-mastery-at-receipt`
(правка формулировки). `glossary.md`, `scenarios.md` (С5 уже описывает
шаги 3–5 на одном экране), `architecture.md`, `ux-concept.md`,
`standards.md`, `antipatterns.md` не затрагиваются: новых терминов,
инвариантов и правил нет.

## 1. Сервисный слой: ячейки у всех Задач, приём и вердикт с отметками

- [x] 1.1 `MasteryService.cellsOf` — ячейки у **всех** Задач состава Задания в порядке выдачи,
  не только с принятой Работой (design.md, решение 4); javadoc класса и метода — без «у Задачи
  без Работы ячеек нет»; `MasteryServiceTest`: ожидание «ячеек без Работы нет» заменить
  на «ячейки есть и у Задачи без Работы»; проверка — `get_diagnostics_for_file`
- [x] 1.2 Фабрика `Mark.fromForm(String[] topics, String[] methods, String[] statuses)` —
  перенос `MasteryController.marksFrom` (разошедшиеся длины — `IllegalArgumentException`
  «Форма ячеек повреждена…», пустое значение — «без изменения»), `MasteryController.mark`
  зовёт её (адрес пока на месте); проверка — `get_diagnostics_for_file`
- [x] 1.3 `StudentWorkService`: зависимость от `MasteryService`; `receive(…, List<Mark> marks)` —
  после `works.create` зовёт `mastery.mark(assignment, problem, marks)` внутри того же `try`,
  отказ убирает уложенные ключи и откатывает Работу; `setVerdict(id, verdict, note, List<Mark> marks)` —
  `works.setVerdict`, затем `mastery.mark(work.assignment(), work.problem(), marks)` (design.md,
  решения 1 и 2); javadoc обоих; `StudentWorkController` пока передаёт `List.of()`; старые
  сигнатуры не сохраняются; проверка — `get_diagnostics_for_file` по обоим файлам
- [x] 1.4 Вызовы `service.receive(` и `service.setVerdict(` в `StudentWorkServiceTest`
  и `WorkFilesTest` — с `List.of()` последним аргументом (скриптом с проверкой, что каждое
  место найдено); проверка — `get_diagnostics_for_file`
- [x] 1.5 `StudentWorkServiceTest`: приём с отметкой «А × X → владеет» пишет Работу и отметку,
  «без изменения» ячейку не трогает; приём без файла с отметкой — ни Работы, ни отметки;
  приём с парой не из разметки — ни Работы, ни отметки; вердикт с отметкой пишет оба;
  вердикт с парой не из разметки — вердикт прежний, отметок нет. `WorkFilesTest`: приём
  с парой не из разметки не оставляет файлов в хранилище; проверка —
  `mvn test -Dtest='StudentWorkServiceTest,WorkFilesTest,MasteryServiceTest'`

## 2. Экран приёма: ячейки в форме приёма, блок «Вердикт и владение»

- [x] 2.1 Запись `ru.locus.work.EnteredReview` (Задача, вердикт, примечание, дата текстом,
  `Map<Cell, MasteryStatus>` выбранных) с методами для шаблона: относится ли к Задаче,
  выбранное значение ячейки (design.md, решение 5); проверка — `get_diagnostics_for_file`
- [x] 2.2 `StudentWorkController.receive` и `setVerdict` читают три списка сырыми
  (`getParameterValues`) и разбирают `Mark.fromForm`; отказ (`IllegalArgumentException`)
  у обоих — `error` и `entered` в модель и экран приёма; `setVerdict` получает `problem`
  из Работы; проверка — `StudentWorkControllerIsThinTest` зелёный
- [x] 2.3 `MasteryController`: `POST /mastery` снят, остаётся `GET /mastery?student=`;
  javadoc без простановки; `AssignmentScreen` — javadoc о двух контроллерах поправить;
  проверка — `MasteryControllerIsThinTest` зелёный
- [x] 2.4 `templates/work/assignment.html`: в «Принять решение» под вердиктом и примечанием —
  подпись «Владение» и ячейки (как в прежнем блоке: скрытые `topic`/`method`, `status`
  с «без изменения»); блоки «Вердикт» и «Владение» — один «Вердикт и владение» с кнопкой
  «Сохранить», раскрывается по `/works/{id}/verdict`; выбранное из `entered` — только в блоке
  своей Задачи; комментарий шаблона переписать; проверка — экран открывается
  (`mvn test -Dtest=WorkScreenTest`)
- [x] 2.5 `MasteryScreenTest` переписать под новые формы: ячейки в форме приёма (четыре,
  «без изменения»), приём с отметкой, приём без файла — ни Работы, ни отметки, «владеет»
  и вердикт выбраны снова, один блок «Вердикт и владение» без отдельного «Владения»,
  выборочная простановка и снятие через «неизвестно» формой вердикта, справка, вердикт
  не меняет ячеек, отказ по паре не из разметки — на том же экране, вердикт прежний;
  проверка — `mvn test -Dtest=MasteryScreenTest`
- [x] 2.6 Тесты, славшие `POST /mastery` (`MasteryAccessTest`, `MasteryIsFilteredByOwnerTest`,
  `MasteryOverviewScreenTest`), — на `POST /works/{id}/verdict` с ячейками; для чужого —
  404 по чужой Работе; посетитель без входа — на вход; `POST /mastery` больше не принимается;
  проверка — `mvn test -Dtest='Mastery*Test,StudentWork*Test,Works*Test,WorkScreenTest'`

## 3. Документы, приёмка, версия

- [ ] 3.1 `openspec/context/domain-model.md`, строка «Простановка отметки»: отметки ставятся
  в форме приёма Работы или в форме её вердикта и сохраняются тем же действием, одной
  транзакцией (`work-mastery-at-receipt`); `CLAUDE.md`, абзац `mastery-marks`: `POST /mastery`
  заменить на «с `work-mastery-at-receipt` — вместе с приёмом и вердиктом»; проверка —
  абзацы на месте, ссылки целы
- [ ] 3.2 Приёмка: `mvn clean package` зелёный
- [ ] 3.3 Поднять версию в `pom.xml` до `3.17.0` и дописать сверху `CHANGELOG.md` строку
  из proposal.md, раздел «Версия»; проверка — `grep -n 3.17.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive work-mastery-at-receipt`, строка бэклога — ✅, коммит архивации, squash
  в `develop`, тег `v3.17.0` на коммит слияния и в `origin`.
