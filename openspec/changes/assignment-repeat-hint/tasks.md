# Tasks

Документы контекста: правится `openspec/context/domain-model.md` — строка
о Раздаче в разделе построенного (`assignments`) дополняется тем, что форма
выдачи называет прежние выдачи, не запрещая повтор. `glossary.md`,
`scenarios.md`, `architecture.md`, `ux-concept.md`, `standards.md`,
`antipatterns.md` и `CLAUDE.md` не затрагиваются: новых терминов,
инвариантов, слоёв и правил нет, сценарий С4 не меняется.

## 1. Прежние выдачи: запрос, сервис, форма

- [x] 1.1 Запись `ru.locus.assignment.PriorIssue` (Задача, дата выдачи, адресат текстом,
  признак Раздачи) и метод `AssignmentRepository.findPriorIssues(UserId, Collection<ProblemId>)` —
  один запрос `union all` двумя ветками, `user_id = ?` на каждой таблице, Раздача — одна строка
  на пару «Задача × Раздача», порядок — дата по убыванию, затем вид и номер (design.md, решение 1);
  пустой набор Задач — пустой список без запроса; проверка — `get_diagnostics_for_file`
- [x] 1.2 `AssignmentRepositoryTest`: выдача Группе из двух Учеников — одна прежняя выдача
  с именем Группы и датой; личная — с именем Ученика; Задание другого владельца с той же
  Задачей не попадает; порядок — новые первыми; проверка — `mvn test -Dtest=AssignmentRepositoryTest`
- [x] 1.3 Запись `PriorIssues` (показанные до трёх и «сколько ещё») и операция
  `AssignmentService.priorIssues(List<ProblemId>)` → `Map<ProblemId, PriorIssues>` под
  `hasRole('TEACHER')`, владелец — `CurrentUser`, предел три (design.md, решения 2 и 3);
  Задача без выдач в ответ не попадает; проверка — `get_diagnostics_for_file`
- [x] 1.4 `AssignmentController.renderForm` кладёт в модель `priorIssues` с ключом —
  значением идентификатора Задачи; `form.html` — строка `<p class="marks marks--repeat">`
  «Уже выдана: …» под разметкой Задачи: Группа в «», Ученик без кавычек, дата `dd.MM.yyyy`,
  «и ещё N» при остатке; `locus.css` — правило `.marks--repeat` цветом `--warn`;
  проверка — `AssignmentControllerIsThinTest` зелёный
- [x] 1.5 `AssignmentScreenTest`: Задача, выданная Группе, — строка с именем Группы и датой;
  выданная Ученику лично — с его именем; выданная другим Учителем — без строки; не выданная —
  без строки; пять выдач — три новейшие и «и ещё 2»; повторная выдача той же Задачи той же
  Группе проходит и даёт вторую Раздачу; проверка — `mvn test -Dtest=AssignmentScreenTest`
- [x] 1.6 `openspec/context/domain-model.md`: в строке «Удаление Задания и Раздачи» (или рядом,
  в описании построенного `assignments`) — форма выдачи называет прежние выдачи Задачи
  вошедшего Учителя и повтор не запрещает; проверка — абзац на месте, ссылки целы
- [x] 1.7 Приёмка: `mvn clean package` зелёный
- [x] 1.8 Поднять версию в `pom.xml` до `3.16.0` и дописать сверху `CHANGELOG.md` строку
  из proposal.md, раздел «Версия»; проверка — `grep -n 3.16.0 pom.xml CHANGELOG.md`

## Workflow follow-up

- `/opsx:archive assignment-repeat-hint`, строка бэклога — ✅, коммит архивации, squash
  в `develop`, тег `v3.16.0` на коммит слияния и в `origin`.
