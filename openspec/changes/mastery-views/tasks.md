## 1. Вопрос библиотеки и распределение

- [x] 1.1 В `ProblemRepository` — `findMethodsUsedByTopic() →
      Map<TaxonomyNodeId, List<SolutionMethodId>>` одним запросом
      `select distinct pt.topic_id, psm.solution_method_id from problem_topic pt
      join problem_solution_method psm on psm.problem_id = pt.problem_id`,
      порядок — по `topic_id`, `solution_method_id`; javadoc — по образцу
      `findMethodsUsedInTopic`: выборка по разметке, а не по словарю
      (инвариант 4), один запрос на весь каталог для экрана Владения,
      без владельца — библиотека (ADR-0027). Открыть через `ProblemService`
      тем же именем, без `@PreAuthorize`, как остальное чтение библиотеки.
      Проверка: `ProblemRepositoryTest` — две Задачи на одной Теме с двумя
      и одним Методом дают у Темы два Метода без повторов; Тема без Задач
      в карте отсутствует; Задача с двумя Темами даёт пару каждой.
- [x] 1.2 Завести в `ru.locus.mastery` запись `Distribution(int mastered,
      int uncertain, int notMastered, int unknown)` по `design.md`,
      «`Distribution`»: конструктор отвергает отрицательные; `plus(Distribution)`;
      `cells()` — сумма четырёх; `static of(Collection<MasteryStatus>)` —
      счёт по статусам ячеек (`UNKNOWN` → `unknown`); `static empty()`.
      Javadoc: имя — из глоссария, единого значения нет и не будет
      (ADR-0013), `NoSingleMasteryValueTest`. Проверка: `DistributionTest` —
      `of` из семи `MASTERED` и одного `NOT_MASTERED` даёт `(7,0,1,0)`,
      из одного и семи — `(1,0,7,0)`, и они не равны; `plus` складывает
      покомпонентно; `empty().cells() == 0`.
- [x] 1.3 Тест `NoSingleMasteryValueTest` отражением: у `Distribution`
      нет ни одного метода с возвращаемым типом `MasteryStatus`, `double`,
      `float`, `BigDecimal`; javadoc теста — признак готовности карточки
      «нигде нет единого значения владения». Проверка: тест зелёный.

## 2. Вид и сервис

- [x] 2.1 Завести записи вида по `design.md`, «Вид экрана»:
      `MasteryBranch(TaxonomyNode node, Distribution distribution,
      List<MasteryBranch> children)`, `MasteryOfMethodRow(SolutionMethod
      method, Distribution distribution)`, `Gap(TaxonomyNodeId topic,
      String topicPath, SolutionMethodId method, String methodName)`,
      `MasteryOverview(Student student, List<MasteryBranch> tree,
      List<MasteryOfMethodRow> methods, List<Gap> gaps)`; конструкторы
      отвергают `null`, списки копируются. Проверка: компилируется,
      `get_diagnostics_for_file` чист.
- [x] 2.2 В `MasteryService` — `overviewOf(StudentId) → MasteryOverview`,
      `@PreAuthorize("hasRole('TEACHER')")`, владелец из `CurrentUser`:
      Ученик через `StudentService.student(id)` (чужой — `StudentNotFoundException`);
      отметки — `marks.findByStudent(owner, student)`; пары —
      `ProblemService.findMethodsUsedByTopic()`; ячейки Темы — Методы
      из пар ∪ Методы из отметок на этой Теме; распределение Темы —
      `Distribution.of` по статусам ячеек (без строки — `UNKNOWN`);
      Раздела — `plus` по потомкам; дерево — обход `TaxonomyService.tree()`;
      Методы — `SolutionMethodService.all()`, в таблицу попадают только
      с `cells() > 0`, распределение — `plus` по Темам с этим Методом
      среди ячеек; пробелы — отметки `NOT_MASTERED` с путём из
      `TaxonomyService.paths()` и именем Метода, порядок — путь Темы, затем
      имя Метода. Javadoc класса дополнить: второй вход области — чтение
      по Ученику; кольца бинов со `StudentService` нет (объяснить, почему
      здесь сервис, а для Работ — репозиторий). Проверка:
      `MasteryOverviewTest` (`IntegrationTest`, через сервис, по образцу
      `MasteryServiceTest`): Тема из четырёх ячеек разметки с «владеет»
      и «не владеет» → `(1,0,1,2)`; Раздел из двух Тем складывает
      поддерево; Метод в двух Темах — сквозь Темы; «семь и один» против
      «один и семь» различаются; десять Методов в словаре, два в разметке →
      `unknown == 2`; узел без Задач в поддереве → `cells() == 0`; Метод
      без ячеек в таблице отсутствует; пробелы — только `NOT_MASTERED`,
      с путём и именем, у Ученика без отметок пусты; чужой Ученик —
      `StudentNotFoundException`; отметка другого Учителя на той же паре
      в распределении не учтена и в пробелах отсутствует.

## 3. Экран

- [ ] 3.1 В `MasteryController` — `GET /mastery` с `@RequestParam(required
      = false) Long student`: без параметра — `redirect:/students`;
      с параметром — `mastery.overviewOf` в модель как `overview`, шаблон
      `mastery/student`. `StudentNotFoundException` не перехватывать.
      Javadoc контроллера обновить: у области появился экран чтения.
      Проверка: `MasteryControllerIsThinTest` по-прежнему зелёный.
- [ ] 3.2 Шаблон `templates/mastery/student.html`: заголовок «Владение —
      <имя Ученика>»; дерево целиком вложенными `<ul>` через
      `th:fragment`, у каждого узла — имя и либо четыре числа
      («владеет N · владеет неуверенно N · не владеет N · неизвестно N»),
      либо «ячеек нет» при `cells() == 0`; таблица «По Методу» — имя
      и четыре числа; перечень «Пробелы» — путь Темы, имя Метода и ссылка
      «Подобрать задачи» на `@{/problems(node=${gap.topic.value},
      method=${gap.method.value})}`; при пустом перечне — «Пробелов нет».
      Ни одного `<form>`, `<select>`, `<input>`. Комментарий шаблона —
      почему только чтение (ADR-0011) и почему четыре числа (ADR-0013).
      Ссылки «К карточке Ученика» и «К списку Учеников». Проверка:
      `MasteryOverviewScreenTest` (`ServerRenderedPageTest`/`Browser`
      по образцу `MasteryScreenTest`): после простановки на экране приёма
      у Темы видны четыре числа; у Раздела без Задач — «ячеек нет»;
      на странице нет `<form` и `<select`; у пробела ссылка
      `/problems?node=T&method=M`, и по ней в результатах — Задача,
      размеченная T и M; `GET /mastery` без параметра ведёт на `/students`.
- [ ] 3.3 В `templates/student/student.html` — ссылка «Владение Ученика»
      на `@{/mastery(student=${student.id.value})}` рядом с Заданиями
      и Работами; комментарий шаблона дополнить. Проверка:
      `MasteryOverviewScreenTest` — на карточке Ученика есть ссылка
      с этим адресом.

## 4. Права и изоляция

- [ ] 4.1 `MasteryAccessTest` дополнить: `GET /mastery?student=` —
      Администратору без роли Учителя отклонён, Учителю доступен,
      без входа — форма входа. Проверка: тест зелёный.
- [ ] 4.2 `MasteryIsFilteredByOwnerTest` дополнить через HTTP двумя
      Учителями: экран Владения чужого Ученика — 404; оба поставили
      «не владеет» своим Ученикам на одной паре библиотеки — у каждого
      на экране один пробел, свой. Проверка: тест зелёный;
      `OwnerIsRequiredByMasteryTest` не менялся и зелёный.

## 5. Приёмка и документы

- [ ] 5.1 Прогнать `mvn clean package`. Проверка: сборка зелёная.
- [ ] 5.2 Проверить признак готовности карточки: единого значения нет —
      `NoSingleMasteryValueTest`, `DistributionTest`; из перечня пробелов
      открывается поиск по паре — `MasteryOverviewScreenTest`. Проверка:
      тесты названы и зелёные.
- [ ] 5.3 Правка документов контекста: `glossary.md` — у `Владение`
      дописать «распределения и пробелы — `mastery-views`»; проверить,
      что `Distribution` в разделе имён помечен построенным;
      `domain-model.md` — абзац после схемы (строка ~80: распределения
      и пробелы построены в `mastery-views`), таблица «Вычисляемое» —
      четыре строки Владения помечены построенными (`MasteryService.overviewOf`);
      `architecture.md` — строка 27 «задумано, но не написано» → построено;
      `scenarios.md` — С4 и С6 без правки смысла, при необходимости
      пометка «построено»; `CLAUDE.md` — раздел «Статус» (абзац про
      `mastery-views`, «Распределений… нет — это `mastery-views`» убрать,
      тринадцать возможностей); `openspec/backlog.md` — строка 17 ✅
      со ссылкой на архив, в карточке — что сделано, что решено сверх
      (ячейки = разметка ∪ суждения; дерево целиком; вне объёма —
      Ученик сквозь поиск), долг (`method-edit-impact`); в карточке
      `mastery-marks` — долг `mastery-views` погашен. `standards.md`,
      `antipatterns.md` не затрагиваются: новых методов класса ADR-0036
      и новых граблей нет. Проверка: `grep -rn "mastery-views" openspec/context
      CLAUDE.md` не находит слов «должна», «долг», «пока нет», «задумано».
- [ ] 5.4 `strategy.md` не правится, но достижение горизонта редакции 1
      (веха 4) фиксируется: в `openspec/backlog.md`, раздел карточки
      `mastery-views`, — фраза «Горизонт редакции 1 стратегии достигнут;
      пересмотр редакции — отдельное решение владельца». Записей журнала
      изменение не заводит; `python3 openspec/context/adr/build_index.py
      --check` зелёный.
