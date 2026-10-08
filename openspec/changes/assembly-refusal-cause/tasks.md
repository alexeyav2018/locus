## 1. Причина отказа в журнале

- [ ] 1.1 Тест в `PdfAssemblyTest`: отказ на неразбираемом PDF и на JPEG с испорченным телом несёт исходное исключение как `cause`; красный до правки
- [ ] 1.2 Тесты в `AssemblyScreenTest`: загрузка битого PDF оставляет в журнале `AssemblyRefusals` строку `WARN` с именем файла и исключением; сборка битого JPEG формой «Новая Задача» — то же; ошибка заполнения формы (нет Метода) в журнал не попадает; текст отказа на экране прежний
- [ ] 1.3 `PdfAssembly`: пять отказов «не разбирается»/«закрыт паролем» получают `cause`; проверка — `get_diagnostics_for_file`
- [ ] 1.4 Новый `AssemblyRefusals.report(RuntimeException)` — `WARN` с текстом отказа и причиной, только если причина есть; вызов в `AssemblyDraftController.upload`, `ProblemController.create` и `refusedEdit`; проверка — `get_diagnostics_for_file`
- [ ] 1.5 `mvn test -Dtest='PdfAssemblyTest,AssemblyScreenTest,AssemblyDraftServiceTest,AssembledProblemTest'` зелёный
- [ ] 1.6 `mvn clean package` зелёный; документы `openspec/context/` и `CLAUDE.md` не затрагиваются — проверить, что правок нет
- [ ] 1.7 Поднять версию в `pom.xml` до 2.1.3 и дописать в `CHANGELOG.md` строку из `proposal.md`, раздел «Версия»
