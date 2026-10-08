## 1. Схема, запись и сервис без Подписи

- [x] 1.1 Миграция `0014-problem-caption-removal.yaml`: `dropColumn` `problem.caption`, комментарий со ссылкой на ADR-0050 и предупреждением о необратимости
- [x] 1.2 `Problem`: убрать `caption`, `hasCaption()`, `normalizedCaption`; Javadoc — номер (ADR-0050)
- [x] 1.3 `ProblemRepository`: `create` без `caption`, убрать `changeCaption`, `caption` из `Row` и всех `select`
- [x] 1.4 `ProblemService.create/edit` без `caption`; `ProblemForm` и `ProblemController` без `caption`
- [x] 1.5 Тесты: вызовы `create`/`edit` без первого аргумента — скриптом по ошибкам компиляции с проверкой образца; удалить тесты подписи (`ProblemTest` — пустая и обрезанная подпись; `ProblemRepositoryTest` — подпись меняется/отсутствует) или переписать на номер
- [x] 1.6 `ProblemFilesTest.failedSavingLeavesNothingInTheStorage`: сбой сохранения внешним ключом через наследника `CharacteristicService` (design.md, решение 3)
- [x] 1.7 Тест миграции: колонки `caption` в таблице `problem` нет (`information_schema`)

## 2. Шаблоны и экранные тесты

- [x] 2.1 `problem/form.html`: поля Подписи нет
- [x] 2.2 `problem/problem.html`, `problem/search.html`, `taxonomy/tree.html`, `assignment/form.html`, `assignment/assignment.html`, `work/assignment.html`: показ подписи убран, номер на месте
- [x] 2.3 Экранные тесты (`ProblemScreenTest`, `ProblemSearchScreenTest`, `RestructureScreenTest`, `ProblemAccessTest` и прочие с подписью): ожидания без подписи; тест «на форме заведения и правки нет поля `caption`»
- [x] 2.4 `mvn clean package` зелёный

## 3. Документы и версия

- [ ] 3.1 `openspec/context/`: `glossary.md` (строка «Подпись», «Задача»), `domain-model.md`, `scenarios.md`, `architecture.md` — без Подписи
- [ ] 3.2 `CLAUDE.md`: абзац `problem-catalog` — номер без Подписи; в сводке решений строка ADR-0050 вместо подразумеваемого ADR-0029
- [ ] 3.3 Поднять версию в `pom.xml` до 3.0.0 и дописать `CHANGELOG.md`: «`problem-caption-removal` — у Задачи нет Подписи: поле убрано с формы и из показа, колонка `caption` удалена вместе с введёнными значениями (ломает: перед выкладкой — копия базы стенда); Задача различается номером»
