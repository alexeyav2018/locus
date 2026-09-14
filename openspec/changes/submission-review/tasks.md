## 1. Схема и настройки

- [ ] 1.1 Завести миграцию `src/main/resources/db/changelog/migrations/0008-submission-review.yaml`,
      два changeset'а по `design.md`, «Схема». `student_work`: `id`
      (autoIncrement, PK), `user_id` (BIGINT, NOT NULL, FK на `user_` без
      каскада), `assignment_id`, `problem_id` (оба BIGINT NOT NULL),
      `received_on` (DATE NOT NULL), `verdict` (VARCHAR(20), NULL),
      `note` (VARCHAR(2000), NULL); уникальность `(assignment_id, problem_id)`
      и `(id, user_id)`; ключи `(assignment_id, problem_id) →
      assignment_problem(assignment_id, problem_id)` и `(assignment_id,
      user_id) → assignment(id, user_id)`, оба без каскада.
      `student_work_file`: `id` (PK), `student_work_id`, `user_id`,
      `file_key` (VARCHAR(200)), `position` (INT), все NOT NULL; ключ
      `(student_work_id, user_id) → student_work(id, user_id)`
      с `onDelete: CASCADE`. Комментарии по образцу `0007-assignments.yaml`:
      почему два ключа с Работы и без каскада, почему Ученика в таблице
      нет, почему `verdict` NULL — это «не проверена», а не флаг. Проверка:
      `mvn test -Dtest=MigrationsOnStartupTest,InconsistentSchemaTest,NotSubmittedIsNotStoredTest`
      зелёные.
- [ ] 1.2 В `application.yaml` — `spring.servlet.multipart.max-file-size: 20MB`
      и `max-request-size: 100MB` с комментарием (снимок с телефона —
      до 10 МБ, приём — несколько снимков, пережатие после приёма, а не
      вместо). Проверка: `mvn test -Dtest=MigrationsOnStartupTest` зелёный
      (контекст поднимается со свойствами).

## 2. Записи, идентификаторы, перечисление

- [ ] 2.1 Завести `StudentWorkId` и `StudentWorkFileId` по образцу
      `AssignmentId`; перечисление `Verdict` (`CORRECT`, `INCORRECT`)
      с русским `title` («верно», «неверно»). Проверка: `StudentWorkIdTest`,
      `StudentWorkFileIdTest`, `VerdictTest` — отказ на неположительном,
      названия двух значений.
- [ ] 2.2 Завести запись `StudentWorkFile(StudentWorkFileId id, FileKey key,
      int position)` и запись `StudentWork(StudentWorkId id, UserId owner,
      AssignmentId assignment, ProblemId problem, LocalDate receivedOn,
      Verdict verdict /* null — не проверена */, String note /* может быть
      пустым */, List<StudentWorkFile> files)` с правилами в компактном
      конструкторе: владелец, Задание, Задача и дата обязательны; файлов
      хотя бы один, в порядке `position`; `List.copyOf`; `note` обрезается,
      `null` → пустая строка; методы `isChecked()` и `hasNote()`. Javadoc
      объясняет, почему у Работы нет Ученика (он у Задания) и почему
      «не проверена» — отсутствие вердикта, а не флаг (ADR-0038).
      Запись `UploadedWorkFile(byte[] content, String contentType)`
      с `isEmpty()`, javadoc — почему своя, а не `UploadedFile` Задач.
      Проверка: `StudentWorkTest` — отказы и законное состояние, в том
      числе Работа без вердикта.

## 3. Репозиторий

- [ ] 3.1 Завести `StudentWorkRepository` на `JdbcClient`, `UserId` в каждом
      публичном методе: `create(UserId, AssignmentId, ProblemId, LocalDate
      receivedOn, Verdict /* nullable */, String note, List<FileKey>)` —
      строка Работы и строки файлов с `position` от 1; `findById(UserId,
      StudentWorkId)` с файлами по `position`; `findByAssignment(UserId,
      AssignmentId)`; `findByStudent(UserId, StudentId)` соединением
      с `assignment`, новые первыми (по `received_on`, затем `id` убыв.);
      `addFiles(UserId, StudentWorkId, List<FileKey>)` — позиции продолжают
      счёт; `deleteFile(UserId, StudentWorkId, StudentWorkFileId)`;
      `setVerdict(UserId, StudentWorkId, Verdict /* nullable */, String
      note)`; `delete(UserId, StudentWorkId)`; `assignmentsWithWork(UserId,
      Collection<AssignmentId>) → Set<AssignmentId>` одним запросом;
      `countByStudent(UserId, StudentId)`. Файлы читаются вторым запросом
      на весь список. Проверка: `StudentWorkRepositoryTest` — каждый метод
      с двумя владельцами; файлы в порядке загрузки; вторая Работа на паре
      не вставляется (нарушение уникальности); Работа по Задаче не из
      состава и по чужому Заданию не вставляется (нарушение ключа);
      удаление Работы уносит строки файлов, Задание и Ученик остаются;
      `assignmentsWithWork` отдаёт только Задания с Работой и только
      владельца.
- [ ] 3.2 Тест `OwnerIsRequiredByWorksTest` по образцу
      `OwnerIsRequiredByStudentsTest`: у каждого публичного метода
      `StudentWorkRepository` среди параметров есть `UserId`, исключений
      нет; в таблицах `student_work`, `student_work_file` есть `user_id`.
      Проверка: тест зелёный.

## 4. Ответы на вопросы Заданий и Учеников

- [ ] 4.1 Завести `WorksOfAssignment implements AssignmentWork` (`@Component`):
      `assignmentsWithWork(currentUser.id(), ids)`; javadoc — Работа
      считается с момента приёма, вердикт не нужен (ADR-0038), зависит
      только от репозитория и `CurrentUser` (цикл бинов через
      `AssignmentService`). `WorksOfStudent implements StudentUsage`:
      `countByStudent(currentUser.id(), student)` → «принято Работ (N)».
      Проверка: `AssignmentWorkAnsweredTest` с `LoggedIn` и `TestClock` —
      просроченное Задание с Работой без вердикта не «не сдано»
      (`AssignmentService.assignment` и `list` с «только несданные»);
      `AssignmentService.delete` отклоняется `AssignmentInUseException`
      с текстом «уже есть Работа»; `deleteBatch` Раздачи из трёх, где
      одно Задание с Работой, отклоняется и не удаляет ни одного; после
      удаления Работы — «не сдано» вернулось, удаление проходит;
      `WorksOfStudentTest` — `StudentService.delete` отклоняется, в тексте
      и «выдано Заданий», и «принято Работ»; считаются только Работы
      вошедшего. `AssignmentWorkDebtTest` и `StudentUsageDebtTest`
      по-прежнему зелёные.
- [ ] 4.2 Поправить пояснения там, где сказано «ответчиков нет»:
      javadoc `AssignmentWork`, класса `AssignmentService`, его методов
      `refuseUnlessNoWork`, `notSubmitted` и поля `works`; javadoc
      `StudentUsage` и `StudentService.refuseUnlessUnused» («Задание
      и Работа уже отвечают, отметки — долг `mastery-marks`»); комментарий
      шаблона `assignment/assignment.html`. Слова, которые ищут тесты
      долга (`submission-review`, `ADR-0037`, `ADR-0016`, `AssignmentWork`,
      `withWork`, `refuseUnlessNoWork`, `assignments`, `mastery-marks`,
      `StudentUsage`, `ADR-0035`), остаются. Проверка:
      `mvn test -Dtest=AssignmentWorkDebtTest,StudentUsageDebtTest` зелёные.

## 5. Сервис

- [ ] 5.1 Завести `StudentWorkService` с `@PreAuthorize("hasRole('TEACHER')")`
      на каждом методе, владелец из `CurrentUser`, часы из `Clock`,
      `FileStorage` и `ImageCompression`: `receive(AssignmentId, ProblemId,
      LocalDate receivedOn /* null — сегодня */, Verdict /* nullable */,
      String note, List<UploadedWorkFile>) → StudentWorkId`
      (`@Transactional`; Задание через `AssignmentService.assignment` —
      чужое = `AssignmentNotFoundException`; Задача в составе, иначе отказ
      «такой Задачи в Задании нет»; пустые файлы отброшены, остаток
      непуст, иначе «нужен хотя бы один файл»; тип каждого — изображение
      или PDF, иначе «принимаются изображения и PDF» — всё до первого
      обращения к хранилищу; укладка каждого файла с пережатием
      изображений; запись; при неудаче записи — удаление уложенных ключей
      и повторный выброс; нарушение уникальности пары —
      `IllegalArgumentException` «Работа по этой Задаче уже принята:
      файлы добавляются к ней»); `work(StudentWorkId) → StudentWork`,
      чужая — `StudentWorkNotFoundException` (`@ResponseStatus(NOT_FOUND)`);
      `ofAssignment(AssignmentId) → Map<ProblemId, StudentWork>` (Задание
      проверяется через `AssignmentService.assignment`); `ofStudent(StudentId)
      → List<StudentWork>` (Ученик через `StudentService.student`);
      `filesOf(StudentWork) → List<LinkedFile(StudentWorkFile, URI)>`
      через `temporaryLink`; `addFiles(StudentWorkId, List<UploadedWorkFile>)`
      тем же порядком, что приём; `deleteFile(StudentWorkId,
      StudentWorkFileId)` — отказ на единственном «Работа без файла
      не существует: удалите Работу целиком», иначе строка, затем ключ;
      `setVerdict(StudentWorkId, Verdict /* nullable */, String note)`;
      `delete(StudentWorkId)` — ключи собраны, строки удалены, затем
      `storage.delete` по каждому. Проверка: `StudentWorkServiceTest`
      с `LoggedIn` — приём с двумя файлами и датой по умолчанию сегодня
      (по `TestClock`); дата в прошлом; без файлов; вторая Работа на паре;
      Задача не из состава; приём после срока; вердикт при приёме
      и позже, смена и снятие; добавление файлов в конец; удаление файла
      и отказ на последнем; удаление Работы и повторный приём; чужое
      Задание и чужая Работа неотличимы от несуществующих; отказ без роли
      Учителя.
- [ ] 5.2 Тест файлов `WorkFilesTest` на локальном хранилище по образцу
      `ProblemFilesTest`: снимок из `src/test/resources/ru/locus/file/large.jpg`
      после приёма меньше исходного, тип JPEG; `rotated.jpg` — ориентация
      сохранена (как в `ImageCompressionTest`); PDF байт в байт по ссылке;
      ссылка без подписи не отдаёт; после удаления файла и после удаления
      Работы ранее выданная ссылка не отдаёт; приём со снимком и файлом
      `text/plain` отклонён, и в хранилище не появилось ни одного файла
      (счёт файлов в `locus.file.local.directory` до и после). Проверка:
      тест зелёный.

## 6. Экраны

- [ ] 6.1 Добавить `Addresses.WORKS = "/works"`. `StudentWorkController`:
      `GET /works` (параметры `assignment` либо `student`; ни одного —
      редирект на `/assignments`; по `assignment` — `AssignmentService.assignment`,
      `problemsOf`, `ofAssignment`, ссылки файлов через `filesOf`,
      `Verdict.values()`, сегодняшняя дата для формы — из сервиса, шаблон
      `work/assignment.html`; по `student` — `StudentService.student`,
      `ofStudent`, шаблон `work/list.html`), `POST /works` (multipart:
      `assignment`, `problem`, `receivedOn`, `verdict` (пусто — нет),
      `note`, `files` — `List<MultipartFile>`; → редирект на экран приёма),
      `POST /works/{id}/files`, `POST /works/{id}/files/{fileId}/deletion`,
      `POST /works/{id}/verdict`, `POST /works/{id}/deletion`; все —
      редирект на экран приёма Задания Работы; `IllegalArgumentException`
      и `MaxUploadSizeExceededException` — экран приёма с сообщением.
      Контроллер тонкий: `UserId` не читает, `CurrentUser.id()` не зовёт.
      Проверка: `StudentWorkControllerIsThinTest` по образцу
      `AssignmentControllerIsThinTest`.
- [ ] 6.2 Шаблоны `templates/work/assignment.html` (экран приёма: заголовок
      с Учеником и сроком, «не сдано», по каждой Задаче состава — номер,
      подпись, Темы и Методы одной фразой, затем либо Работа: «получена
      dd.MM.yyyy», «не проверена» или вердикт, примечание, файлы ссылками
      с `target="_blank"` и формой удаления у каждого, формы «добавить
      файлы» (`multiple`, `accept="image/*,application/pdf"`), «вердикт
      и примечание» (радио «верно», «неверно», «не проверять сейчас»),
      «удалить Работу»; либо форма приёма с теми же полями и датой
      получения) и `templates/work/list.html` (Работы Ученика: дата,
      вердикт или «не проверена», номер Задачи, ссылка «к Заданию» на экран
      приёма). Оба — с `<meta name="viewport" content="width=device-width,
      initial-scale=1">`, поля на всю ширину, без таблиц и фиксированных
      ширин; фразы, видимые слитно, — в одном `th:text`; пояснения —
      комментариями Thymeleaf. Страница Задания — ссылка «Работы»
      на `/works?assignment={id}`; карточка Ученика — «Работы Ученика»
      на `/works?student={id}`. Проверка: `ServerRenderedPageTest`
      зелёный; `AssignmentScreenTest` и `StudentScreenTest` по-прежнему
      зелёные.
- [ ] 6.3 В тестовом `Browser` — перегрузка `postMultipart(String action,
      Map<String, String> fields, List<FilePart> files)` с записью
      `FilePart(String field, String filename, String contentType, byte[]
      content)`; прежняя перегрузка зовёт новую. Тест `WorkScreenTest`:
      экран приёма Задания из двух Задач с формой у каждой; приём двух
      снимков одним полем `files` — на экране «получена», «не проверена»,
      две ссылки на файлы, у второй Задачи по-прежнему форма; вердикт
      позже — «неверно» и примечание видны; добавление файла — три ссылки;
      удаление файла; отказ на последнем — текст; удаление Работы — снова
      форма; без файлов — текст; список Работ Ученика — обе Работы по двум
      Заданиям, новые первыми, ссылка на экран приёма; ссылки «Работы»
      на странице Задания и «Работы Ученика» на карточке Ученика ведут
      на эти экраны; в разметке обоих экранов есть `viewport`. Проверка:
      тест зелёный.
- [ ] 6.4 Тест доступа `StudentWorkAccessTest` по образцу
      `AssignmentAccessTest`: невошедший — форма входа; Администратор без
      роли Учителя — 403 на экране приёма, списке и каждой операции;
      Учитель — 200 или редирект. Проверка: тест зелёный.

## 7. Изоляция по владельцу

- [ ] 7.1 Тест `WorksAreFilteredByOwnerTest` через HTTP двумя Учителями
      по образцу `AssignmentsAreFilteredByOwnerTest`: экран приёма Задания
      А для Б — 404; список Работ Ученика А для Б — 404; приём Б по Заданию
      А — 404 и Работ не появилось; вердикт, добавление и удаление файлов,
      удаление Работы А от Б — 404 и Работа А прежняя; обратная половина —
      оба приняли Работы по одной Задаче библиотеки и каждый видит только
      свою. Проверка: тест зелёный.

## 8. Приёмка и документы

- [ ] 8.1 Прогнать `mvn clean package`. Проверка: сборка зелёная.
- [ ] 8.2 Проверить признак готовности карточки: скан невозможно получить
      по постоянному адресу — `WorkFilesTest` (5.2, ссылка без подписи
      и после истечения); Работа без файла не сохраняется —
      `StudentWorkServiceTest` и `WorkScreenTest` (5.1, 6.3); интерфейс
      загрузки работает на узком экране — `viewport` и отсутствие
      фиксированных ширин, `WorkScreenTest` (6.3). Проверка: тесты названы
      и зелёные.
- [ ] 8.3 Правка документов контекста: `glossary.md` — перечисление
      `Verdict` в таблице перечислений; `domain-model.md` — Работа
      из замысла становится существующей (абзац после схемы), инвариант 12
      получает вторую половину («Работы нет» отвечает `submission-review`,
      сторожит `AssignmentWorkAnsweredTest`), таблица операций — «Удаление
      Ученика» (Задание и Работа отвечают, отметки — долг), «Удаление
      Задания и Раздачи» (вопрос отвечен), новая строка «Удаление Работы»
      (свободно, ADR-0038); `architecture.md` — первый абзац «построено»
      и таблица «Файлы» (поток сканов существует); `standards.md` — раздел
      «Файлы»: приём нескольких файлов — проверки до первой укладки,
      уборка всех уложенных при неудаче записи; `CLAUDE.md` — раздел
      «Статус» («Работ … нет» больше нельзя писать; одиннадцать
      возможностей) и сводка ключевых решений (ADR-0038);
      `openspec/backlog.md` — строка 15 получает ✅ и ссылку на архив,
      в карточке — что решено сверх (одна Работа на пару, вердикт отдельно,
      удаление свободно, пределы загрузки) и долг (`mastery-marks`: отметки
      на том же экране, справка «решено 2 из 6»). `scenarios.md`,
      `antipatterns.md`, `strategy.md` не затрагиваются: С5 описан как
      есть, предпосылки не задеты, условия возврата не сработали.
- [ ] 8.4 Перевести [ADR-0038](../../context/adr/0038-rabota-odna-na-paru-i-udalyaetsya-svobodno.md)
      в статус «Принято», пересобрать индекс:
      `python3 openspec/context/adr/build_index.py --check` зелёный.
