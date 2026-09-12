## 1. Схема и часы

- [x] 1.1 Завести миграцию `src/main/resources/db/changelog/migrations/0007-assignments.yaml`,
      три changeset'а по `design.md`, «Схема». `assignment_batch`: `id`
      (autoIncrement, PK), `user_id` (BIGINT, NOT NULL, FK на `user_` без
      каскада), `group_name` (VARCHAR(200), NOT NULL), `issued_on` (DATE,
      NOT NULL); уникальность `(id, user_id)`. `assignment`: `id`, `user_id`,
      `student_id` (NOT NULL), `assignment_batch_id` (NULL), `issued_on`,
      `due_date` (оба DATE NOT NULL), `theory_scope` (VARCHAR(30) NOT NULL);
      составные ключи `(student_id, user_id) → student(id, user_id)`
      и `(assignment_batch_id, user_id) → assignment_batch(id, user_id)`,
      оба без каскада; уникальность `(id, user_id)`. `assignment_problem`:
      `assignment_id`, `problem_id`, `user_id`, `position` (INT), все NOT NULL;
      PK `(assignment_id, problem_id)`; ключ `(assignment_id, user_id)
      → assignment(id, user_id)` с `onDelete: CASCADE`, ключ `problem_id
      → problem(id)` без каскада. Комментарии по образцу
      `0006-students-groups.yaml`: почему имя Группы текстом, почему ключи
      без каскада, почему нет колонки «сдано». Проверка:
      `mvn test -Dtest=MigrationsOnStartupTest,InconsistentSchemaTest` зелёные.
- [x] 1.2 Завести `ru.locus.TimeConfiguration` с бином `Clock`
      (`Clock.systemDefaultZone()`), javadoc — почему один бин на приложение
      и почему часы хранилища не трогаются. В тестовых исходниках —
      `ru.locus.TestClock`: `@Component @Primary`, наследник `Clock`
      со сдвигом `Duration`, который тест ставит (`shift(Duration)`)
      и снимает (`reset()`); без сдвига — системные часы. Проверка:
      `TestClockTest` — после `shift(Duration.ofDays(3))` `LocalDate.now(clock)`
      на три дня позже, после `reset()` — сегодня; контекст интеграционных
      тестов поднимается один (`mvn test -Dtest=CurrentUserTest,TestClockTest`
      без второго старта контекста в логе).

## 2. Записи, идентификаторы, перечисление

- [ ] 2.1 Завести `AssignmentId` и `AssignmentBatchId` по образцу `StudentId`;
      перечисление `TheoryScope` (`NONE`, `TOPICS`, `TOPICS_AND_SECTIONS`)
      с русским `title` по словарю («без теории», «только Темы задач»,
      «вместе с Разделами»). Проверка: `AssignmentIdTest`,
      `AssignmentBatchIdTest`, `TheoryScopeTest` — отказ на неположительном,
      названия трёх значений.
- [ ] 2.2 Завести запись `Assignment(AssignmentId id, UserId owner, StudentId
      student, AssignmentBatchId batch /* null — выдано лично */, LocalDate
      issuedOn, LocalDate dueDate, TheoryScope theoryScope, List<ProblemId>
      problems)` с правилами в компактном конструкторе: владелец, Ученик,
      даты и охват обязательны; Задач хотя бы одна, без повторов (порядок
      сохраняется); `List.copyOf`. Запись `AssignmentBatch(AssignmentBatchId id,
      UserId owner, String groupName, LocalDate issuedOn)`: имя непустое после
      обрезки. Javadoc объясняет, почему у Задания нет поля «сдано»
      (инвариант 12) и почему Раздача помнит имя, а не Группу. Проверка:
      `AssignmentTest`, `AssignmentBatchTest` — отказы и законное состояние,
      в том числе Задание без Раздачи.
- [ ] 2.3 Завести `Addressee` — запечатанный интерфейс с `ToStudent(StudentId)`
      и `ToGroup(GroupId)` и фабрикой `of(Long student, Long group)`:
      отказ `IllegalArgumentException` на «ни одного» и «оба», сообщение
      «адресат один: Ученик либо Группа». Проверка: `AddresseeTest`.

## 3. Репозитории

- [ ] 3.1 Завести `AssignmentBatchRepository` на `JdbcClient`:
      `create(UserId, String groupName, LocalDate issuedOn)`,
      `findById(UserId, AssignmentBatchId)`, `findAll(UserId)` (новые первыми),
      `delete(UserId, AssignmentBatchId)`; в каждом SQL `user_id = ?`.
      Проверка: `AssignmentBatchRepositoryTest` — каждый метод с двумя
      владельцами.
- [ ] 3.2 Завести `AssignmentRepository`: `create(UserId, StudentId,
      AssignmentBatchId /* nullable */, LocalDate issuedOn, LocalDate dueDate,
      TheoryScope, List<ProblemId>)` — строка Задания и строки состава
      с `position` в одной вставке; `findById(UserId, AssignmentId)` с составом
      по `position`; `find(UserId, StudentId /* nullable */, AssignmentBatchId
      /* nullable */, LocalDate from /* nullable */, LocalDate to /* nullable */)`
      — по сроку, затем по `id`, состав подгружается одним вторым запросом,
      период включительно; `findByBatch(UserId, AssignmentBatchId)`;
      `changeDueDate(UserId, AssignmentId, LocalDate)`; `delete(UserId,
      AssignmentId)`; `deleteByBatch(UserId, AssignmentBatchId)`;
      `countByStudent(UserId, StudentId)`; и **`countByProblem(ProblemId)`
      без владельца** — javadoc называет ADR-0036 и объясняет, почему
      владельца подставить некому. Проверка: `AssignmentRepositoryTest` —
      каждый метод с двумя владельцами; состав читается в порядке выдачи;
      Задание чужому Ученику не вставляется (нарушение составного ключа);
      удаление Задания уносит состав, Ученик и Задача остаются;
      `countByProblem` считает Задания обоих владельцев.
- [ ] 3.3 Тест `OwnerIsRequiredByAssignmentsTest` по образцу
      `OwnerIsRequiredByStudentsTest`: у каждого публичного метода
      `AssignmentRepository` и `AssignmentBatchRepository` среди параметров
      есть `UserId`, **кроме** перечисленных поимённо в списке исключений
      с причиной — `AssignmentRepository.countByProblem` (ADR-0036); в таблицах
      `assignment_batch`, `assignment`, `assignment_problem` есть `user_id`.
      Javadoc теста объясняет, почему список поимённый, а не по признаку
      «возвращает число». Проверка: тест зелёный; добавление метода без
      владельца вне списка его роняет (проверить временной правкой и откатить).
- [ ] 3.4 Тест `NotSubmittedIsNotStoredTest`: поимённый состав колонок трёх
      таблиц — ровно те, что в 1.1; javadoc: «не сдано» вычисляется
      (инвариант 12, ADR-0016), колонка со сдачей или просрочкой уронит тест.
      Проверка: тест зелёный.

## 4. Вопросы и ответы между областями

- [ ] 4.1 Завести `AssignmentWork` в `ru.locus.assignment`
      (`Set<AssignmentId> withWork(Collection<AssignmentId>)`), javadoc
      по образцу `StudentUsage`: реализаций нет, обязан ответить
      `submission-review`, ссылка на ADR-0037 и ADR-0016 (оба употребления —
      «не сдано» и отказ в удалении). Проверка: компилируется.
- [ ] 4.2 Завести `AssignmentsOfProblem implements ProblemUsage` (`@Component`):
      `countByProblem` → «вошла в Задания (N)» или пусто; javadoc — ADR-0036,
      наружу только число. `AssignmentsOfStudent implements StudentUsage`:
      `countByStudent(currentUser.id(), student)` → «выдано Заданий (N)».
      Оба зависят только от репозитория и `CurrentUser`. Проверка:
      `AssignmentsOfProblemTest` — Задача в Заданиях двух Учителей считается
      по обоим; `AssignmentsOfStudentTest` — считаются только Задания
      вошедшего; `ProblemUsageDebtTest` и `StudentUsageDebtTest` по-прежнему
      зелёные.
- [ ] 4.3 Поправить javadoc `NodeContent.countVanishing`: «единственное
      исключение» → класс исключений по ADR-0036, ссылка на него.
      Проверка: `MasteryRestructureDebtTest` зелёный.
- [ ] 4.4 Тест долга `AssignmentWorkDebtTest` по образцу `ProblemUsageDebtTest`:
      в `AssignmentService` есть названные методы `refuseUnlessNoWork`
      и вычисление «не сдано» спрашивает `AssignmentWork`; исходники сервиса
      и вопроса называют `submission-review`, ADR-0037 и ADR-0016. Проверка:
      тест зелёный (пишется вместе с 5.1, зелёный после неё).

## 5. Сервис

- [ ] 5.1 Добавить `ProblemService.problems(List<ProblemId>) → List<FoundProblem>`
      в порядке запроса (репозиторий: `findByIds`), отсутствующая —
      `IllegalArgumentException` «Задачи № … не существует»; `named`
      переиспользуется. Проверка: новый случай в `ProblemServiceTest`;
      `OwnerIsUnknownToProblemsTest` зелёный.
- [ ] 5.2 Завести `AssignmentService` с `@PreAuthorize("hasRole('TEACHER')")`
      на каждом методе, владелец из `CurrentUser`, часы из `Clock`,
      `List<AssignmentWork>`: `issueToStudent(StudentId, List<ProblemId>,
      LocalDate, TheoryScope) → AssignmentId` (Ученик через
      `StudentService.student` — чужой = `StudentNotFoundException`; Задачи
      через `problems(ids)`; повторы сняты; срок обязателен;
      `issuedOn = LocalDate.now(clock)`); `issueToGroup(GroupId, …) →
      AssignmentBatchId` (`@Transactional`; Группа через `GroupService.group`,
      члены через `members`; пустая — отказ «Группа пуста» до записи; Раздача
      с именем Группы, затем Задание каждому); `assignment(AssignmentId) →
      ListedAssignment` (Задание, имя Ученика, `notSubmitted`), чужое —
      `AssignmentNotFoundException` (`@ResponseStatus(NOT_FOUND)`);
      `batch(AssignmentBatchId) → AssignmentBatch` и `ofBatch(…) →
      List<ListedAssignment>`, чужая — `AssignmentBatchNotFoundException`;
      `list(AssignmentFilter) → List<ListedAssignment>` с отбором
      «только несданные» после чтения; `batches()` для выбора в отборе;
      `problemsOf(Assignment) → List<FoundProblem>`; `theoryOf(Assignment)
      → List<NodeTheory>` по охвату через `TheoryService.materialsOn`
      с дедупликацией по идентификатору материала; `changeDueDate(AssignmentId,
      LocalDate)`; `delete(AssignmentId)` и `deleteBatch(AssignmentBatchId)`
      через `refuseUnlessNoWork(List<Assignment>)` с
      `AssignmentInUseException`. Записи `ListedAssignment(Assignment, String
      studentName, boolean notSubmitted)` и `AssignmentFilter(StudentId, AssignmentBatchId,
      LocalDate from, LocalDate to, boolean onlyNotSubmitted)`. «Не сдано» —
      отдельный названный приватный метод: `dueDate` раньше `LocalDate.now(clock)`
      и Задания нет в `withWork`. Проверка: `AssignmentServiceTest`
      с `LoggedIn.as(account)` — выдача Ученику; Группе из двенадцати
      порождает двенадцать с одной Раздачей; пустая Группа; без Задач;
      несуществующая Задача; повтор снят; чужой Ученик и чужая Группа
      неотличимы от несуществующих; перенос срока; удаление Задания
      и Раздачи; отказ без роли Учителя.
- [ ] 5.3 Тест «не сдано» `NotSubmittedTest` на `TestClock`: срок сегодня —
      не несдано; срок вчера — несдано; выдано со сроком через два дня,
      сдвиг часов на три дня — несдано без единого действия; сдвиг снят —
      снова нет; отбор «только несданные за период» из спеки (позавчера,
      вчера, завтра). Проверка: тест зелёный.
- [ ] 5.4 Тест теории `AssignmentTheoryTest`: материал на Теме и на её Разделе —
      `TOPICS` даёт только первый, `TOPICS_AND_SECTIONS` оба с именами узлов,
      `NONE` — пусто; материал, добавленный после выдачи, виден; материал
      Раздела по двум Темам — один раз. Проверка: тест зелёный.
- [ ] 5.5 Тест заморозки и неудаляемости `AssignmentsFreezeTheLibraryTest`:
      после выдачи `ProblemService.edit`, `replaceCondition`, `replaceSolution`,
      `delete` отклоняются с `ProblemInUseException`, в тексте — число Заданий;
      `StudentService.delete` отклоняется с `StudentInUseException`; после
      удаления единственного Задания и правка, и удаление снова проходят;
      перестройка (`rehomeTopic`) над выданной Задачей по-прежнему проходит
      (`RestructureBypassesFreezeTest` зелёный). Проверка: тест зелёный.

## 6. Экраны

- [ ] 6.1 Добавить `Addresses.ASSIGNMENTS = "/assignments"`. `AssignmentController`:
      `GET /assignments` (сводка: параметры `student`, `batch`, `from`, `to`,
      `notSubmitted`; списки Учеников и Раздач для отбора), `GET /assignments/new`
      (параметр `problem` повторяющийся; Задачи через `problems(ids)`;
      Ученики и Группы вошедшего; `TheoryScope.values()`), `POST /assignments`
      (`Addressee.of(student, group)`; по `ToStudent` → `issueToStudent`
      и редирект на Задание, по `ToGroup` → `issueToGroup` и редирект
      на Раздачу; `IllegalArgumentException` — форма с сообщением),
      `GET /assignments/{id}`, `POST /assignments/{id}/due-date`,
      `POST /assignments/{id}/deletion` (→ на сводку),
      `GET /assignments/batches/{id}`, `POST /assignments/batches/{id}/deletion`.
      Контроллер тонкий: `UserId` не читает, `CurrentUser.id()` не зовёт.
      Проверка: `AssignmentControllerIsThinTest` по образцу
      `StudentControllerIsThinTest`.
- [ ] 6.2 Шаблоны `templates/assignment/list.html`, `assignment/form.html`,
      `assignment/assignment.html`, `assignment/batch.html` по образцу
      `student/` и `problem/search.html`; даты через `#temporals.format(…, 'dd.MM.yyyy')`;
      «не сдано» — отдельным словом в одном `th:text`; ссылки на `/problems/{id}`
      и `/theory/{id}`; отказ удаления показывается текстом. Экран поиска
      `problem/search.html`: под `th:if="${teacher}"` — отметка `problem`
      у каждой найденной Задачи и форма `GET /assignments/new` с кнопкой
      «Выдать отмеченные»; `ProblemSearchController` кладёт `teacher`
      из `CurrentUser.account()`. Карточка Ученика `student/student.html` —
      ссылка «Задания Ученика» на `/assignments?student={id}`; главная —
      «Задания» под `teacher`. Проверка: `ServerRenderedPageTest` зелёный;
      `ProblemSearchScreenTest` — Учитель видит отметки, Администратор без
      роли Учителя — нет.
- [ ] 6.3 Тест `AssignmentScreenTest`: от поиска к форме с двумя отмеченными
      Задачами; выдача Ученику через форму и страница Задания с составом,
      сроком и Учеником; выдача Группе и страница Раздачи с именем Группы
      и Учениками; переименование и удаление Группы после выдачи не меняют
      Раздачу; без срока, без Задач, оба адресата — сообщение; перенос срока;
      удаление Задания и Раздачи; сводка с отбором по Ученику и «только
      несданные» на `TestClock`. Проверка: тест зелёный.
- [ ] 6.4 Тест доступа `AssignmentAccessTest` по образцу `StudentAccessTest`:
      невошедший — форма входа; Администратор без роли Учителя — 403 на сводке,
      форме, выдаче и каждой операции; Учитель — 200. Проверка: тест зелёный.

## 7. Изоляция по владельцу

- [ ] 7.1 Тест `AssignmentsAreFilteredByOwnerTest` через HTTP двумя Учителями
      по образцу `StudentsAreFilteredByOwnerTest`: Задание и Раздача Учителя А
      не показываются в сводке Б; прямой адрес Задания и Раздачи А для Б — 404;
      перенос срока и удаление от Б не меняют данных А; выдача Б Ученику
      и Группе А — как несуществующим, Заданий не создаётся; обратная
      половина — оба выдали одну Задачу и видят её с одной разметкой и одним
      материалом. Проверка: тест зелёный.

## 8. Приёмка и документы

- [ ] 8.1 Прогнать `mvn clean package`. Проверка: сборка зелёная.
- [ ] 8.2 Проверить признак готовности карточки: выдача Группе из двенадцати
      порождает двенадцать Заданий с общей Раздачей (5.2); «не сдано» нигде
      не хранится — `NotSubmittedIsNotStoredTest` (3.4); сдвиг системного
      времени меняет состояние без действий — `NotSubmittedTest` (5.3).
      Проверка: три теста названы и зелёные.
- [ ] 8.3 Правка документов контекста: `glossary.md` — перечисление
      `TheoryScope` в таблице перечислений; `domain-model.md` — Задание
      и Раздача из замысла становятся существующими, инвариант 12
      подтверждается тестом, таблица операций — перенос срока и удаление
      Задания по ADR-0037, удаление Ученика — «Задание уже отвечает»;
      `architecture.md` — первый абзац «построено»; `standards.md`, «Данные» —
      «единственное исключение» заменяется классом ADR-0036 с тремя границами
      и поимённым списком в тесте формы; `CLAUDE.md` — раздел «Статус»
      («Заданий … нет» больше нельзя писать) и список возможностей;
      `openspec/backlog.md` — строка 13 получает ✅ и ссылку на архив,
      в карточке — что решено сверх и долг (`AssignmentWork`, выбытие);
      **новая строка и карточка `student-withdrawal`** (решение владельца
      на гейте): выбытие ученика — «занимался, перестал, в списках мешает,
      историю терять нельзя»; класс `—`, зависимость `assignments`,
      стоимость S, не ломает, личное; основание — последствия ADR-0035;
      развилки без решения: флаг «выбыл» на карточке / отдельный список
      выбывших / только скрытие из выбора при выдаче.
      `scenarios.md`, `antipatterns.md`, `strategy.md` не затрагиваются:
      С4 и С7 описаны как есть, предпосылки не задеты, условия возврата
      не сработали.
- [ ] 8.4 Перевести [ADR-0036](../../context/adr/0036-voprosy-biblioteki-bez-vladelca.md)
      и [ADR-0037](../../context/adr/0037-zadanie-neizmenno-posle-vydachi.md)
      в статус «Принято», пересобрать индекс:
      `python3 openspec/context/adr/build_index.py --check` зелёный.
