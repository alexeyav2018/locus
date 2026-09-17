## 1. Схема

- [x] 1.1 Завести миграцию `src/main/resources/db/changelog/migrations/0009-mastery-marks.yaml`,
      один changeset по `design.md`, «Схема»: таблица `mastery` с колонками
      `user_id`, `student_id`, `topic_id`, `solution_method_id` (все BIGINT
      NOT NULL), `status` (VARCHAR(20) NOT NULL); составной первичный ключ
      `pk_mastery (user_id, student_id, topic_id, solution_method_id)`;
      ключи `fk_mastery_user → user_(id)`, `fk_mastery_student
      (student_id, user_id) → student(id, user_id)`, `fk_mastery_node
      topic_id → taxonomy_node(id)`, `fk_mastery_solution_method → solution_method(id)`,
      все без каскада. Комментарии по образцу `0008-submission-review.yaml`:
      почему нет `id`, почему `UNKNOWN` в колонку не пишется (ADR-0039),
      почему ключи на дерево и словарь без каскада — второй рубеж
      за ответчиками (antipatterns.md, «Молчаливое удаление Темы»), почему
      ключ на `student(id, user_id)`. Подключить в `db.changelog-master.yaml`.
      Проверка: `mvn test -Dtest=MigrationsOnStartupTest,InconsistentSchemaTest`
      зелёные.

## 2. Записи и перечисление

- [x] 2.1 Завести пакет `ru.locus.mastery`: перечисление `MasteryStatus`
      (`UNKNOWN`, `NOT_MASTERED`, `UNCERTAIN`, `MASTERED`) с русским `title`
      («неизвестно», «не владеет», «владеет неуверенно», «владеет»)
      и методом `isJudgement()` (`false` только у `UNKNOWN`), javadoc —
      `UNKNOWN` в базу не пишется (ADR-0039), `UNKNOWN` и `NOT_MASTERED`
      различаются везде (ADR-0012). Запись `MasteryCell(TaxonomyNodeId
      topic, String topicPath, SolutionMethodId method, String methodName,
      MasteryStatus status, int solved, int checked)` с `hint()` → «решено
      N из M». Запись `Mark(TaxonomyNodeId topic, SolutionMethodId method,
      MasteryStatus status /* null — без изменения */)`. Запись-ключ
      `Cell(TaxonomyNodeId topic, SolutionMethodId method)`. Проверка:
      `MasteryStatusTest` — четыре названия, `isJudgement`; `MasteryCellTest` —
      `hint()` на «2 из 3» и «0 из 0».

## 3. Репозиторий

- [x] 3.1 Завести `MasteryRepository` на `JdbcClient` по `design.md`,
      «Репозиторий»: `put(UserId, StudentId, TaxonomyNodeId,
      SolutionMethodId, MasteryStatus)` — `INSERT … ON CONFLICT
      (user_id, student_id, topic_id, solution_method_id) DO UPDATE SET
      status`, а при `UNKNOWN` — `DELETE`; `findByStudent(UserId, StudentId)
      → Map<Cell, MasteryStatus>`; `countByStudent(UserId, StudentId)`;
      без владельца — `countByTopic(TaxonomyNodeId)`,
      `countByMethod(SolutionMethodId)`, `rehomeTopic(TaxonomyNodeId from,
      TaxonomyNodeId to)` тремя запросами в порядке «слить → убрать
      занятые → перевесить», `deleteByTopic(TaxonomyNodeId)`. Javadoc класса —
      четыре метода без владельца названы, с причиной (ADR-0036) и ссылкой
      на `OwnerIsRequiredByMasteryTest`. Проверка: `MasteryRepositoryTest` —
      `put` заводит, перезаписывает, `UNKNOWN` удаляет; `findByStudent`
      и `countByStudent` двумя владельцами; отметка чужому Ученику
      не вставляется (нарушение ключа); `countByTopic`/`countByMethod`
      считают обоих владельцев; `rehomeTopic`: переезд на пустой приёмник,
      слияние совпавших (остаётся одна), слияние разошедшихся (`UNCERTAIN`),
      смесь — в одном вызове; `deleteByTopic` не трогает соседние Темы.
- [x] 3.2 Тест `OwnerIsRequiredByMasteryTest` по образцу
      `OwnerIsRequiredByAssignmentsTest`: у каждого публичного метода
      `MasteryRepository` среди параметров есть `UserId`, кроме четырёх
      перечисленных поимённо с причиной (`countByTopic`, `countByMethod`,
      `rehomeTopic`, `deleteByTopic` — вопросы и действия дерева и словаря
      о своей сущности, ADR-0036); в таблице `mastery` есть `user_id`.
      Проверка: тест зелёный.
- [x] 3.3 В `StudentWorkRepository` — `countCheckedByPairs(UserId, StudentId)
      → Map<Cell, Solved>` (`Solved(int correct, int checked)` в `work`;
      ключ — пара `(topic_id, solution_method_id)`), одним запросом:
      `student_work ⋈ assignment (student_id, user_id) ⋈ problem_topic ⋈
      problem_solution_method`, `WHERE verdict IS NOT NULL`, группировка
      по паре, `correct` — `verdict = 'CORRECT'`. Возвращаемый тип ключа —
      своя запись `work.ProblemPair(TaxonomyNodeId, SolutionMethodId)`,
      чтобы `work` не зависел от `mastery`. Проверка:
      `StudentWorkRepositoryTest` — три проверенные и одна непроверенная
      Работа на паре дают `(2, 3)`; пара без Работ в карте отсутствует;
      Работы другого владельца и другого Ученика не считаются;
      `OwnerIsRequiredByWorksTest` по-прежнему зелёный.

## 4. Ответы на вопросы дерева, Учеников и словаря

- [ ] 4.1 Завести `MasteryOnNode implements NodeContent` (`@Component`,
      зависимость только от `MasteryRepository`): `on` и `requiringTopic` —
      «на Теме стоят отметки Владения (N)»; `moveTopicContent` →
      `rehomeTopic`; `distributeTopicContent` → `deleteByTopic`
      (распределение не читается — отметки исчезают, ADR-0007);
      `countVanishing` → `countByTopic`. Javadoc — почему через
      репозиторий, а не сервис (`design.md`, «Ответы»). Переписать
      `MasteryRestructureDebtTest` в `ru.locus.mastery.MasteryRestructureTest`
      (`IntegrationTest`, два Учителя через `LoggedIn`, Администратор):
      `TaxonomyService.countVanishingMarks` на Теме с двумя отметками
      Учителя А и одной Б — 3; `deleteWithDistribution` — на снятой Теме
      отметок нет, на приёмниках прежние; `create` с приёмником-потомком —
      отметки на потомке; с существующим приёмником и занятой ячейкой —
      `UNCERTAIN` при расхождении, одна при совпадении; обычный `delete`
      Темы с отметками — `NodeNotEmptyException` с текстом «отметки
      Владения»; `create` без приёмника у Темы с отметками — отказ.
      В `TaxonomyServiceTest` и `ProblemsGuardTheTreeTest` утверждения
      «всегда ноль» переформулировать как «ноль, пока отметок нет».
      Проверка: `MasteryRestructureTest`, `TaxonomyServiceTest`,
      `ProblemsGuardTheTreeTest`, `TaxonomyKnowsNothingOfProblemsTest`
      зелёные; `MasteryRestructureDebtTest` удалён.
- [ ] 4.2 Завести `MasteryOfStudent implements StudentUsage` (`@Component`,
      владелец из `CurrentUser`) — «вынесено суждений (N)». Поправить
      `StudentUsageDebtTest`: третий ответчик пришёл — тест проверяет, что
      `StudentUsage` и `StudentService.refuseUnlessUnused` называют все три
      работы как ответившие; javadoc `StudentUsage` и `StudentService`
      («отметки — `mastery-marks`, ответила»). Тест `MasteryOfStudentTest`:
      `StudentService.delete` Ученика с отметкой отклоняется, в тексте
      «суждений (1)»; с Заданием, Работой и отметкой — все три причины;
      после `put(UNKNOWN)` и удаления Задания — удаляется; чужие отметки
      не считаются. Проверка: тесты зелёные.
- [ ] 4.3 Завести `MasteryOfMethod implements DictionaryUsage` (`@Component`):
      `ofMethod` — «опираются отметки Владения (N)» по `countByMethod`,
      `ofCharacteristic` — пусто с пояснением. Поправить
      `DeletionCheckDebtTest` (`mastery-marks` — ответила) и javadoc
      `SolutionMethodService.refuseUnlessUnused`, `DictionaryUsage`.
      Тест `MasteryGuardsTheMethodTest` по образцу
      `ProblemsGuardTheDictionariesTest`: Метод с отметкой Учителя
      не удаляется Администратором — `EntryInUseException` с числом
      по обоим Учителям; после снятия отметок и разметки — удаляется;
      Характеристику отметки не держат. Проверка: тесты зелёные,
      `DictionariesAreNotFilteredTest` по-прежнему зелёный.

## 5. Сервис

- [ ] 5.1 Завести `MasteryService` с `@PreAuthorize("hasRole('TEACHER')")`
      на каждом методе, владелец из `CurrentUser`, зависимости:
      `MasteryRepository`, `AssignmentService`, `StudentWorkRepository`,
      `TaxonomyService` (пути Тем), `SolutionMethodService` (имена Методов).
      `cellsOf(AssignmentId) → Map<ProblemId, List<MasteryCell>>`: Задание
      через `AssignmentService.assignment` (чужое — 404), состав через
      `problemsOf`, Работы через `StudentWorkRepository.findByAssignment`;
      ячейки только у Задач с Работой, порядок — Темы в порядке разметки ×
      Методы в порядке разметки; значения из `findByStudent`, справка из
      `countCheckedByPairs` — по одному запросу на экран. `mark(AssignmentId,
      ProblemId, List<Mark>)` — `@Transactional`; Задание своё; Задача
      в составе, иначе «такой Задачи в Задании нет»; Работа принята, иначе
      «отметки ставятся по принятой Работе»; каждая пара ∈ `topics × methods`
      Задачи, иначе «ячейки нет: пара не из разметки Задачи»; `status ==
      null` пропускается; остальное — `put`. Проверка: `MasteryServiceTest`
      с `LoggedIn` — ячейки у Задачи 2×2 после приёма Работы, все
      `UNKNOWN`; до приёма — пусто; выборочная простановка двух из четырёх;
      перезапись; `UNKNOWN` снимает; пара не из разметки — отказ и ничего
      не изменилось; Задача не из состава — отказ; без Работы — отказ;
      вердикт не меняет ячеек; удаление Работы не снимает отметку; справка
      «2 из 3» при трёх проверенных и одной непроверенной по другому
      Заданию того же Ученика; чужое Задание — `AssignmentNotFoundException`;
      без роли Учителя — отказ.

## 6. Экран

- [ ] 6.1 Добавить `Addresses.MASTERY = "/mastery"`. `MasteryController`:
      `POST /mastery` с `assignment`, `problem`, параллельными списками
      `topic`, `method`, `status` (пустая строка — без изменения) →
      `List<Mark>` по индексу → `MasteryService.mark`; редирект на
      `/works?assignment=`; `IllegalArgumentException` — экран приёма
      с сообщением (через редирект с параметром `error` не делать —
      отрисовать `work/assignment` тем же способом, что
      `StudentWorkController`: вынести `renderAssignment` в общий
      компонент `work.AssignmentScreen` либо повторить модель; выбрать
      первое). В `StudentWorkController.renderAssignment` — атрибут `cells`
      от `MasteryService.cellsOf` и `statuses = MasteryStatus.values()`.
      Контроллеры тонкие. Проверка: `MasteryControllerIsThinTest` по образцу
      `StudentWorkControllerIsThinTest`; `StudentWorkControllerIsThinTest`
      по-прежнему зелёный.
- [ ] 6.2 Шаблон `templates/work/assignment.html`: в блоке принятой Работы,
      после формы вердикта, — раздел «Владение» с формой `POST /mastery`:
      по ячейке — подпись «путь Темы × имя Метода», текущее значение
      и справка одной фразой в одном `th:text` («сейчас: неизвестно ·
      решено 2 из 3»), скрытые `topic` и `method`, `<select name="status">`
      с пунктом «без изменения» (значение пусто, выбран) и четырьмя
      значениями шкалы; кнопка «Сохранить отметки». Без таблиц
      и фиксированных ширин; комментарий шаблона — предзаполнения нет
      (ADR-0011), `UNKNOWN` — снятие (ADR-0039); убрать фразу «Отметок
      Владения здесь пока нет». Тест `MasteryScreenTest` (`Browser`):
      у Задачи без Работы раздела нет; после приёма — четыре ячейки
      с «неизвестно»; сохранение двух — на экране новые значения, две
      прежние; возврат в «неизвестно»; справка после трёх проверенных
      Работ по другому Заданию; вердикт «верно» не меняет ячеек;
      `viewport` на месте. Проверка: тест зелёный, `WorkScreenTest`
      и `ServerRenderedPageTest` по-прежнему зелёные.
- [ ] 6.3 Тест доступа `MasteryAccessTest` по образцу
      `StudentWorkAccessTest`: невошедший — форма входа; Администратор
      без роли Учителя — 403 на `POST /mastery`; Учитель — редирект.
      Проверка: тест зелёный.

## 7. Изоляция по владельцу

- [ ] 7.1 Тест `MasteryIsFilteredByOwnerTest` через HTTP двумя Учителями
      по образцу `WorksAreFilteredByOwnerTest`: `POST /mastery` Б по Заданию
      А — 404 и отметок А не появилось и не изменилось; оба ставят отметки
      своим Ученикам на одной паре библиотеки — на экране приёма каждый
      видит свою; `countByTopic` при этом считает обе (сторона ADR-0036 —
      в `MasteryRestructureTest`). Проверка: тест зелёный.

## 8. Приёмка и документы

- [ ] 8.1 Прогнать `mvn clean package`. Проверка: сборка зелёная.
- [ ] 8.2 Проверить признак готовности карточки: ячейка не создаётся для
      пары без Задачи — `MasteryServiceTest` (пара не из разметки);
      вердикт не влияет на ячейки — `MasteryServiceTest`, `MasteryScreenTest`;
      новое значение затирает прежнее без истории — `MasteryRepositoryTest`,
      `MasteryServiceTest`; вторая половина признака `rubricator-restructure`
      («верное число отметок по всем учителям») — `MasteryRestructureTest`.
      Проверка: тесты названы и зелёные.
- [ ] 8.3 Правка документов контекста: `glossary.md` — у `Mastery` пометка
      «построено в `mastery-marks`», у `UNKNOWN` — «отсутствие строки
      (ADR-0039)»; `domain-model.md` — абзац после схемы («Владение
      по-прежнему замысел» → построено), инвариант 7 при необходимости,
      таблица операций: «Удаление Ученика» (три ответчика), «Добавление
      потомков Теме» (отметки переезжают, слияние по ADR-0039), «Удаление
      Темы» (счёт настоящий), новая строка «Простановка отметки»
      и «Удаление Метода»; `standards.md` — «Сегодня в классе два метода»
      → перечислить `MasteryRepository.countByTopic`, `countByMethod`,
      `rehomeTopic`, `deleteByTopic`; `architecture.md` — первый абзац
      «построено», если он перечисляет области; `scenarios.md` — С5, шаг 4
      без правки, С2 (строка 55: «пока отметок в системе нет») — снять
      оговорку; `CLAUDE.md` — раздел «Статус» (абзац про `mastery-marks`,
      «Отметок владения по-прежнему нет» убрать, двенадцать возможностей),
      сводка решений (ADR-0039); `openspec/backlog.md` — строка 16 ✅
      со ссылкой на архив, в карточке — что решено сверх (ADR-0039)
      и долг (`mastery-views`, `method-edit-impact`); в карточке
      `rubricator-restructure` — долг погашен. `antipatterns.md`,
      `strategy.md` не затрагиваются: новых граблей нет, предпосылки
      не задеты. Проверка: `grep -rn "mastery-marks" openspec/context
      CLAUDE.md` не находит слов «должна», «долг», «пока нет».
- [ ] 8.4 Перевести [ADR-0039](../../context/adr/0039-otmetka-tolko-s-suzhdeniem.md)
      в статус «Принято», пересобрать индекс:
      `python3 openspec/context/adr/build_index.py --check` зелёный.
