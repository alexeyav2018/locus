## Context

Мотивация — в `proposal.md`, требования — в `specs/`. Здесь — как это
ложится на существующий код.

Что уже есть и на что опирается отметка:

- Задача (`ru.locus.problem.Problem`) несёт `topics` (`TaxonomyNodeId`)
  и `methods` (`SolutionMethodId`) — ячейки-кандидаты есть их произведение,
  без единого нового запроса. Экран приёма уже получает состав Задания
  как `List<FoundProblem>` (`AssignmentService.problemsOf`) с путями Тем
  и именами Методов — нужные подписи ячеек там же.
- Работа (`ru.locus.work.StudentWork`) — на паре «Задание × Задача»,
  с `verdict` (`null` — не проверена). Ученика у Работы нет, он у Задания:
  справка «решено N из M» идёт соединением `student_work → assignment →
  student`, как `StudentWorkRepository.countByStudent`.
- Три вопроса с готовыми местами под ответ: `taxonomy.NodeContent`
  (пять методов, из них `countVanishing` — без владельца, класс ADR-0036),
  `student.StudentUsage.of`, `dictionary.DictionaryUsage.ofMethod`.
  Ответчики собираются списком; спрашивающие не знают ни одного по имени
  (`…KnowsNothingOf…`).
- Образцы: `work` как последняя личная область (репозиторий на `JdbcClient`
  с `UserId` в каждом методе, `OwnerIsRequiredByWorksTest`, ответчики
  `WorksOfStudent`/`WorksOfAssignment` с владельцем из `CurrentUser`);
  `ProblemsOnNode` как ответчик дерева с переездом и распределением через
  сервис, взятый `@Lazy`.

Ограничения: Администратор не видит личного контура (ADR-0005), кроме
чисел, отданных по вопросам библиотеки (ADR-0036); шаблоны не вычисляют
(standards.md); экран приёма — под телефон.

## Goals / Non-Goals

**Goals:**

- Одна новая личная область `ru.locus.mastery`, замкнутая на три ответа
  и один экранный вход — блок ячеек на экране приёма.
- Все три теста долга переписаны на проверку настоящих ответов, а не
  удалены.
- Второй метод класса ADR-0036 назван поимённо, вместе с действиями
  без владельца, которые дерево требует от отметок.

**Non-Goals:**

- Распределения по статусам, перечень пробелов, подбор задач по ним —
  `mastery-views`. Экран «таблица владения» — не будет никогда.
- Поведение при правке Методов у размеченной Задачи — `method-edit-impact`.
- История отметок — `mastery-history`.
- Отдельный экран отметок Ученика: карточка Ученика не меняется. Единственное
  место простановки и просмотра — экран приёма; это следствие ADR-0011
  («точечно, в момент проверки»).

## Decisions

### Область `mastery` без собственного экрана — она пристраивается к экрану приёма

Ячейки живут в блоке принятой Работы на `templates/work/assignment.html`,
и `StudentWorkController.renderAssignment` добавляет в модель
`Map<ProblemId, List<MasteryCell>>` от `MasteryService.cellsOf(assignment)`.
Форма простановки шлёт `POST /mastery` в `MasteryController` (свой контроллер,
своя область), который после простановки возвращает на экран приёма.

Почему так, а не форма в `StudentWorkController`: простановка — операция
области отметок с её правами и проверками; `work` о ней знает лишь то, что
показывает. Зависимость `work → mastery` (показ) в одну сторону;
`mastery → work` нет — справка считается в `StudentWorkRepository` по
`UserId`, и `MasteryService` зовёт **репозиторий** Работ, а не сервис:
иначе `StudentWorkService → StudentService → List<StudentUsage> →
MasteryOfStudent` и `MasteryService → StudentWorkService` замкнули бы кольцо
бинов. Тот же довод, по которому `WorksOfStudent` зависит от репозитория.

Альтернатива — отдельный экран `/mastery?student=` с полной таблицей —
отвергнута ADR-0011: форма для заполнения всей таблицы вредна.

### Схема: одна таблица `mastery` с составным ключом, владелец в ключе

```
mastery
  user_id             BIGINT NOT NULL  FK user_
  student_id          BIGINT NOT NULL
  topic_id            BIGINT NOT NULL  FK taxonomy_node   (без каскада)
  solution_method_id  BIGINT NOT NULL  FK solution_method (без каскада)
  status              VARCHAR(20) NOT NULL   — NOT_MASTERED | UNCERTAIN | MASTERED
  PK (user_id, student_id, topic_id, solution_method_id)
  FK (student_id, user_id) → student(id, user_id)   (без каскада)
```

- **Суррогатного `id` нет**: отметку никто не адресует по номеру — ни адрес
  формы, ни ссылка; ключ ячейки и есть её имя. Единственная таблица
  проекта без `id`, и это сказано в комментарии миграции.
- **`UNKNOWN` в колонку не пишется** (ADR-0039): строка — суждение.
  Проверка `CHECK`-ом не ставится — второго рубежа здесь нет, потому что
  единственный вход в таблицу — `MasteryRepository.put`, который
  `UNKNOWN` превращает в `delete`.
- **Ключ на `student(id, user_id)`** — отметка чужому Ученику не вставится
  и в обход сервиса, как Работа по чужому Заданию.
- **Ключи на `taxonomy_node` и `solution_method` без каскада** — второй
  рубеж за ответчиками дерева и словаря: забыли ответить — база откажет,
  а не унесёт суждения молча (antipatterns.md, «Молчаливое удаление Темы»).
- Пары «Тема × Метод» без Задачи ключом не выразить (это соединение двух
  таблиц разметки); правило ставит сервис.

Альтернатива — таблица ячеек-кандидатов с `UNKNOWN` по строке — отвергнута
в ADR-0039.

### Репозиторий: `UserId` везде, кроме четырёх методов класса ADR-0036

`MasteryRepository` на `JdbcClient`:

| метод | владелец | назначение |
| --- | --- | --- |
| `put(UserId, StudentId, TaxonomyNodeId, SolutionMethodId, MasteryStatus)` | да | `INSERT … ON CONFLICT DO UPDATE`; `UNKNOWN` → `delete` |
| `findByStudent(UserId, StudentId) → Map<Cell, MasteryStatus>` | да | текущие значения для ячеек экрана |
| `countByStudent(UserId, StudentId)` | да | ответ `StudentUsage` |
| `countByTopic(TaxonomyNodeId)` | **нет** | `NodeContent.on`, `requiringTopic`, `countVanishing` |
| `countByMethod(SolutionMethodId)` | **нет** | `DictionaryUsage.ofMethod` |
| `rehomeTopic(TaxonomyNodeId from, TaxonomyNodeId to)` | **нет** | `NodeContent.moveTopicContent`, со слиянием |
| `deleteByTopic(TaxonomyNodeId)` | **нет** | `NodeContent.distributeTopicContent` |

Четыре метода без владельца — вопросы и действия библиотеки к личным
контурам о своей сущности (Теме, Методе). Два из них — **действия**, а не
счёт: дерево велит отметкам переехать или исчезнуть у всех Учителей сразу,
и владельца у операции нет по существу — её совершает Администратор над
общим узлом (ADR-0007). Границы класса соблюдены: наружу — только число,
спрашивает библиотека через интерфейс-вопрос, методы перечислены поимённо
в `OwnerIsRequiredByMasteryTest`. В `standards.md` список «сегодня в классе»
пополняется.

Слияние при переезде — тремя запросами в одной транзакции сервиса дерева:

1. на приёмнике `status := UNCERTAIN` там, где у той же тройки (владелец,
   Ученик, Метод) строка есть на обеих Темах и значения разошлись;
2. удалить переезжающие строки, у которых на приёмнике тройка занята;
3. оставшимся переезжающим `topic_id := to`.

Порядок важен: сначала слить, потом убрать, потом перевесить — иначе
перевешивание упадёт на ключе.

### Сервис: три входа, права на каждом

`MasteryService`, `@PreAuthorize("hasRole('TEACHER')")`, владелец из
`CurrentUser`:

- `cellsOf(AssignmentId) → Map<ProblemId, List<MasteryCell>>` — Задание
  через `AssignmentService.assignment` (чужое — 404), Задачи через
  `problemsOf`; Работы через `StudentWorkRepository.findByAssignment`
  (ячейки только у Задач с Работой); значения `findByStudent`; справка —
  `StudentWorkRepository.countCheckedByPair(UserId, StudentId, TaxonomyNodeId,
  SolutionMethodId) → Solved(correct, checked)` одним запросом на все пары
  Ученика (`countCheckedByPairs` → `Map<Cell, Solved>`), а не по ячейке.
  `MasteryCell(TaxonomyNodeId topic, String topicPath, SolutionMethodId
  method, String methodName, MasteryStatus status, int solved, int checked)`.
- `mark(AssignmentId, ProblemId, List<Mark>)` — `@Transactional`; Задание
  своё; Задача в составе; Работа по ней принята; каждая пара ∈ `topics ×
  methods` Задачи, иначе `IllegalArgumentException` «ячейки нет: пара
  не из разметки Задачи»; `Mark.status == null` — без изменения,
  пропускается; `UNKNOWN` — снятие. Ученик — `assignment.student()`.
- `today()` не нужен; часов у области нет.

Кандидаты вычисляются каждый раз из разметки — не хранятся (standards.md,
«Вычислимое вычисляется в запросе»).

### Ответы: три `@Component`, зависимости только от репозитория

- `MasteryOnNode implements NodeContent` — `on` и `requiringTopic`: «на Теме
  стоят отметки Владения (N)»; `moveTopicContent` → `rehomeTopic`;
  `distributeTopicContent` → `deleteByTopic` (распределение не читается:
  отметки не распределяются, а исчезают); `countVanishing` → `countByTopic`.
  Зависит от репозитория напрямую, без сервиса: у операций нет ни владельца,
  ни прав Учителя — их совершает Администратор через `TaxonomyService`,
  где и стоят право и транзакция. Это отличие от `ProblemsOnNode`, который
  идёт через `ProblemService.rehomeTopic`: там переезд — правка разметки
  Задачи со своими правилами; здесь у отметки правил переезда, кроме
  слияния, нет, а слияние — дело репозитория.
- `MasteryOfStudent implements StudentUsage` — «вынесено суждений (N)»,
  владелец из `CurrentUser`.
- `MasteryOfMethod implements DictionaryUsage` — `ofMethod`: «опираются
  отметки Владения (N)»; `ofCharacteristic` — пусто (Характеристика
  в измерении владения не участвует, glossary.md).

### Форма ячеек: параллельные списки, «без изменения» по умолчанию

У каждой Работы одна форма `POST /mastery` с `assignment`, `problem`
и по ячейке — скрытые `topic`, `method` и `<select name="status">`
с первым пунктом «без изменения» (пустое значение), затем четыре значения
шкалы, текущее — помечено в подписи ячейки, а не выбрано в списке: выбранное
в списке текущее значение при сохранении ничего бы не меняло, но читалось
бы как предзаполнение. Контроллер собирает три списка в `List<Mark>`
по индексу. Кнопка «Сохранить отметки» одна на Задачу.

Альтернатива — форма на каждую ячейку — на телефоне даёт по кнопке на
строку и по перезагрузке на отметку; отвергнута.

### Тесты долга переписываются, не удаляются

- `MasteryRestructureDebtTest` → `MasteryRestructureTest` (в `mastery`):
  `countVanishingMarks` считает отметки двух Учителей на одной Теме;
  `deleteWithDistribution` уносит их и не трогает приёмники; `create`
  с приёмником переносит и сливает. Утверждения «всегда ноль»
  в `TaxonomyServiceTest` и `ProblemsGuardTheTreeTest` меняются
  на «ноль, пока отметок нет».
- `StudentUsageDebtTest` теряет третью «должна» и проверяет, что все три
  ответчика есть; `MasteryOfStudentTest` — отказ в удалении называет
  число суждений вместе с прочими причинами.
- `DeletionCheckDebtTest` — «mastery-marks» становится «ответила»;
  `MasteryGuardsTheMethodTest` — отказ с числом.

## Risks / Trade-offs

- [Тема с отметками, но без Задач: экран дерева не предложит перестройку,
  а обычные удаление и углубление откажут] → сегодня такое состояние
  недостижимо: отметка ставится только при принятой Работе, Работа — по
  Заданию, Задание замораживает Задачу, перестройка двигает отметки вместе
  с Задачами. Отказы `on`/`requiringTopic` — второй рубеж; путь, которым
  состояние станет достижимым, — `method-edit-impact`, и ему это решать.
- [Слияние при переезде огрубляет два суждения до `UNCERTAIN`] → случай
  редкий (Администратор выбрал существующую Тему с отметками того же
  Ученика по тому же Методу), касается одной ячейки; альтернативы — отказ
  или потеря — хуже (ADR-0039).
- [Справка считается на каждый показ экрана приёма — запрос с соединением
  четырёх таблиц] → один запрос на Ученика, группировка по паре; на десятках
  Работ незаметно. Хранить — нельзя (standards.md).
- [Учитель в спешке выберет значение не в той ячейке] → ячейка подписана
  полным путём Темы и именем Метода, значение по умолчанию — «без
  изменения»; исправление — повторная простановка, истории нет.
- [Действия без владельца в личной таблице (`rehomeTopic`,
  `deleteByTopic`)] → зовутся только из ответчика дерева, под правом
  Администратора в `TaxonomyService`; перечислены поимённо с причиной
  в `OwnerIsRequiredByMasteryTest`; `MasteryService` их не зовёт.

## Migration Plan

Одна миграция `0009-mastery-marks.yaml`, только `createTable`
и ограничения; данных для переноса нет. Отката нет (ADR-0023). Обновление
с остановкой (ADR-0024).
