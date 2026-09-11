## 1. Схема

- [x] 1.1 Завести миграцию `src/main/resources/db/changelog/migrations/0006-students-groups.yaml`,
      три changeset'а. `student`: `id` (autoIncrement, PK), `user_id` (BIGINT,
      NOT NULL, FK на `user_` **без каскада**), `name` (VARCHAR(200), NOT NULL);
      уникальность `(id, user_id)`. `group_`: те же три колонки и та же
      уникальность плюс уникальный индекс `(user_id, lower(name))` сырым SQL,
      как в `0003-library-dictionaries.yaml:75`. `group_student`: `group_id`,
      `student_id`, `user_id` (все NOT NULL), первичный ключ `(group_id, student_id)`,
      составные внешние ключи `(group_id, user_id) → group_(id, user_id)`
      и `(student_id, user_id) → student(id, user_id)`, оба `onDelete: CASCADE`.
      Комментарии по образцу `0005-theory-materials.yaml`: почему `user_id`
      здесь есть, а в библиотечных таблицах нет. Проверка:
      `mvn test -Dtest=MigrationsOnStartupTest` и `InconsistentSchemaTest` зелёные.

## 2. Тестовая оснастка личного контура

- [x] 2.1 Добавить `roles` в `TestAccounts.Account` и `LoggedIn.as(TestAccounts.Account)`:
      токен с именем входа записи и её ролями, чтобы `CurrentUser.id()` отдавал
      настоящий `UserId`. Прежний `LoggedIn.as(Role…)` не трогать. Проверка:
      новый случай в `CurrentUserTest` — после `LoggedIn.as(account)`
      `currentUser.id()` равен `account.id()`; весь `CurrentUserTest` зелёный.

## 3. Ученик: запись, идентификатор, репозиторий

- [x] 3.1 Завести `StudentId` по образцу `SolutionMethodId` и запись `Student`
      (`StudentId`, `UserId owner`, `String name`) с правилами в компактном
      конструкторе: имя непустое после обрезки, владелец обязателен. Проверка:
      `StudentIdTest` и `StudentTest` — отказы и законное состояние.
- [x] 3.2 Завести `StudentRepository` на `JdbcClient`: `findAll(UserId)`,
      `findById(UserId, StudentId)`, `create(UserId, String)`,
      `rename(UserId, StudentId, String)`, `delete(UserId, StudentId)`;
      в каждом SQL — `user_id = ?`. Javadoc объясняет, почему у каждого метода
      есть владелец (ADR-0027). Проверка: `StudentRepositoryTest` на
      Testcontainers — для **каждого** метода два владельца: А видит и правит
      своё, на чужом получает пусто или ноль строк; после `delete` строка
      исчезла; одинаковые имена у одного владельца допустимы.
- [x] 3.3 Тест `OwnerIsRequiredByStudentsTest` — зеркало `OwnerIsUnknownToTheoryTest`:
      у каждого публичного метода `StudentRepository` и `GroupRepository`
      (последний — с задачи 5.2, список классов пополняется там) среди
      параметров есть `UserId`; в таблицах `student` и `group_` есть колонка
      `user_id`. Проверка: тест зелёный на репозитории Учеников.

## 4. Ученик: сервис

- [x] 4.1 Завести `StudentUsage` в `ru.locus.student` по образцу `ProblemUsage`
      (`Optional<String> of(StudentId)`), javadoc называет `assignments`,
      `submission-review`, `mastery-marks` и ADR-0035. Проверка: файл
      на месте, компилируется.
- [x] 4.2 Завести `StudentService`: `all()`, `student(StudentId)`, `create(String)`,
      `rename(StudentId, String)`, `delete(StudentId)`; на каждом —
      `@PreAuthorize("hasRole('TEACHER')")`; владелец берётся из
      `CurrentUser.id()` и передаётся в репозиторий; несуществующий или чужой
      — `StudentNotFoundException` с `@ResponseStatus(NOT_FOUND)`; удаление
      через отдельный метод `refuseUnlessUnused(Student)`, перебирающий
      `List<StudentUsage>`. Проверка: `StudentServiceTest` с `LoggedIn.as(account)`
      — заведение, переименование, удаление, отказ на пустом имени, чужой
      Ученик неотличим от несуществующего; отказ Пользователю без роли Учителя
      (`AccessDeniedException`).
- [x] 4.3 Тест долга `StudentUsageDebtTest` по образцу `ProblemUsageDebtTest`:
      `refuseUnlessUnused` на месте, исходники `StudentService` и `StudentUsage`
      называют все три работы и ADR-0035. Проверка: тест зелёный.

## 5. Группа: запись, идентификатор, репозиторий

- [x] 5.1 Завести `GroupId` и запись `Group` (`GroupId`, `UserId owner`,
      `String name`) с теми же правилами, что у `Student`. Проверка: `GroupIdTest`,
      `GroupTest`.
- [x] 5.2 Завести `GroupRepository`: `findAll(UserId)`, `findById(UserId, GroupId)`,
      `findByName(UserId, String)` (без учёта регистра, тем же сравнением, что
      индекс), `create(UserId, String)`, `rename(UserId, GroupId, String)`,
      `delete(UserId, GroupId)`, `members(UserId, GroupId)` → `List<StudentId>`,
      `setMembers(UserId, GroupId, List<StudentId>)` (удалить прежние, вставить
      новые, `user_id` в каждой строке), `groupsOf(UserId, StudentId)` → Группы
      Ученика. Внести `GroupRepository` в `OwnerIsRequiredByStudentsTest`.
      Проверка: `GroupRepositoryTest` — каждый метод с двумя владельцами;
      состав с чужим Учеником не вставляется (нарушение составного ключа);
      удаление Группы уносит членство, Ученики остаются; удаление Ученика
      вычёркивает его из Групп; `OwnerIsRequiredByStudentsTest` зелёный.

## 6. Группа: сервис

- [x] 6.1 Завести `GroupService`: `all()`, `group(GroupId)`, `create(String)`,
      `rename(GroupId, String)`, `delete(GroupId)`, `members(GroupId)`
      → `List<Student>`, `setMembers(GroupId, List<StudentId>)`,
      `groupsOf(StudentId)`; `@PreAuthorize("hasRole('TEACHER')")` на каждом;
      имя проверяется на занятость через `findByName(owner, …)` с исключением
      самой записи при переименовании (`NameAlreadyTakenException` области);
      в `setMembers` каждый идентификатор читается через
      `StudentRepository.findById(owner, id)`, чужой или несуществующий —
      `StudentNotFoundException`; `GroupNotFoundException` для чужой Группы.
      Проверка: `GroupServiceTest` — заведение, занятое имя в другом регистре,
      одинаковые имена у разных владельцев допустимы, состав задаётся целиком,
      чужой Ученик в составе отвергается и состав не меняется, удаление Группы
      не трогает Учеников, отказ без роли Учителя.

## 7. Экраны

- [x] 7.1 Добавить `Addresses.STUDENTS = "/students"` и `Addresses.GROUPS = "/groups"`;
      `StudentController`: список с формой заведения, карточка (имя, форма
      переименования, Группы Ученика, удаление), обработчики `POST` по образцу
      `DictionaryController`; после удаления — на список. `GroupController`:
      список с формой заведения, страница Группы (имя, форма переименования,
      состав галочками по всем Ученикам владельца, удаление). Контроллеры
      тонкие: `UserId` не читают и не передают. Проверка:
      `StudentControllerIsThinTest` по образцу `DictionaryControllerIsThinTest`
      (оба контроллера; дополнительно — ни один метод не принимает `UserId`
      и не зовёт `CurrentUser.id()`).
- [x] 7.2 Шаблоны `templates/student/list.html`, `student/student.html`,
      `group/list.html`, `group/group.html` по образцу `dictionary/dictionaries.html`
      и `user/list.html`; фразы, которые человек видит слитно, — в одном
      `th:text`. Главная `home.html`: ссылки «Ученики» и «Группы» под
      `th:if="${teacher}"`, `HomeController` кладёт `teacher`; текст «ученики
      появятся позже» заменить. Проверка: `ServerRenderedPageTest` зелёный.
- [x] 7.3 Тест `StudentScreenTest`: список Учеников, заведение через форму,
      карточка, переименование, удаление, пустое имя показывает сообщение;
      `GroupScreenTest`: список, заведение, занятое имя, состав галочками
      сохраняется и показывается, удаление. Проверка: оба зелёные.
- [x] 7.4 Тест доступа `StudentAccessTest` по образцу `ProblemAccessTest`:
      невошедший получает форму входа; Администратор без роли Учителя — 403
      на списке, карточке и каждой операции над Учеником и Группой; Учитель —
      200. Проверка: тест зелёный.

## 8. Изоляция по владельцу

- [x] 8.1 Тест `StudentsAreFilteredByOwnerTest` через HTTP двумя Учителями,
      по образцу `ProblemsAreNotFilteredTest`: Ученик и Группа Учителя А
      не показываются в списках Учителя Б; прямой адрес карточки и Группы
      А для Б отвечает так же, как несуществующий (404); переименование,
      удаление и состав от Б не меняют данных А; Б не может включить Ученика
      А в свою Группу — состав не меняется. Обратная половина, перенесённая
      из `auth-roles`: оба Учителя, заведя по Ученику, видят на `/taxonomy`
      и `/problems` одну и ту же Задачу и один и тот же материал.
      Проверка: тест зелёный.
- [x] 8.2 Тест `StudentIsNotAUserTest`: в таблице `student` ровно колонки
      `id`, `user_id`, `name` — ни имени входа, ни пароля; вход с именем
      Ученика в качестве имени входа отклоняется так же, как несуществующее
      имя. Проверка: тест зелёный.

## 9. Приёмка и документы

- [x] 9.1 Прогнать `mvn clean package`. Проверка: сборка зелёная.
- [x] 9.2 Проверить признак готовности карточки: Ученик не имеет учётной записи
      и не может войти (8.2); тест на невидимость чужих учеников и обратный —
      библиотека видна обоим целиком (8.1). Проверка: оба теста названы
      в карточке и зелёные.
- [x] 9.3 Правка документов контекста: `domain-model.md` — Ученик и Группа
      из замысла становятся существующими, инвариант 11 подтверждается схемой
      и тестом, в таблице операций — удаление Ученика по ADR-0035;
      `architecture.md` — первый абзац «построено» пополняется Учениками
      и Группами; `standards.md` — правило ADR-0027 получает ссылку на первую
      личную область и зеркальный тест `OwnerIsRequiredBy…`; `CLAUDE.md` —
      раздел «Статус» (учеников больше «нет» нельзя писать) и список
      возможностей `openspec/specs/`; `openspec/backlog.md` — строка 12
      получает ✅ и ссылку на архив. `glossary.md`, `scenarios.md`,
      `antipatterns.md`, `strategy.md` изменение не затрагивает: С3 описан
      как есть, термины названы.
- [x] 9.4 Перевести [ADR-0035](../../context/adr/0035-udalenie-uchenika-i-gruppy.md)
      в статус «Принято», пересобрать индекс:
      `python3 openspec/context/adr/build_index.py --check` зелёный.
