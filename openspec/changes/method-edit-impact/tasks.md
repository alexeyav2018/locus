# Tasks

## 1. Регрессионный тест

- [ ] 1.1 В `src/test/java/ru/locus/mastery/` завести тест (например,
      `MethodCannotBeUnmarkedUnderMastery.java`), воспроизводящий сквозной
      сценарий: `library.problem(topic)` с Методом → `AssignmentService`
      выдаёт её Ученику (замораживает Задачу, ADR-0030) → фикстурой заводится
      принятая Работа (`StudentWorkRepository`, по образцу
      `MasteryServiceTest`) → `MasteryRepository.put` ставит отметку на паре
      «Тема × Метод» этой Задачи. Проверить: `mvn test
      -Dtest=MethodCannotBeUnmarkedUnderMastery` зелёный.
- [ ] 1.2 В том же тесте — попытка `ProblemService.edit(...)` с тем же
      составом Тем, но без этого Метода, падает `ProblemInUseException`
      (как в `AssignmentsFreezeTheLibraryTest.issuedProblemIsFrozenAgainstEveryHandEdit`).
      Проверить утверждением на классе исключения и на тексте («вошла
      в Задания»).
- [ ] 1.3 В том же тесте — после отказа отметка (`MasteryRepository.findByStudent`)
      и разметка Задачи (`ProblemService.problem(id).methods()`) не изменились.
      Проверить прогоном того же теста.
- [ ] 1.4 Добавить в Javadoc класса ссылку на ADR-0011 («Последствия»),
      ADR-0030, ADR-0037 и на элемент бэклога `method-edit-impact` — по
      образцу `MasteryGuardsTheMethodTest`, закрывающего симметричную
      половину дыры (удаление Метода из словаря). Проверить: `find_symbol`
      по классу отдаёт итоговый Javadoc.

## 2. Закрытие карточки бэклога

- [ ] 2.1 В `openspec/backlog.md`, строка 18 (`method-edit-impact`) — пометить
      статус ✅ и добавить в карточку раздела 4 блок вида «> ✅ Выполнено
      <дата>. Change: ...», объясняющий, что дыра закрыта побочным эффектом
      ADR-0030 и ADR-0037, а не новым правилом, и закреплена регрессионным
      тестом. Проверить: строка таблицы и карточка совпадают по статусу.
- [ ] 2.2 `openspec/context/adr/0011-otmetka-na-pare.md` не правится: запись
      неизменяема, новое решение не принимается. Проверить: файл не изменён
      (`git diff` по нему пуст).
- [ ] 2.3 `CLAUDE.md` не правится: элемент не входит в перечисленные
      тринадцать возможностей и не меняет их список. Проверить: файл
      не изменён.
