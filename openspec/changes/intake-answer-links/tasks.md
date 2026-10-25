## 1. Отрисовка ответа и подпись счётчика

- [x] 1.1 `AnswerMarkdownTest`: фрагмент кода `/problems/intake/1` —
      ссылка с `from` на Разговор и текстом адреса; фрагменты
      `find_problems`, `https://example.com`, `/`, `/2`,
      `/problems и т.д.` — код без ссылки; фрагмент кода внутри
      Markdown-ссылки — код внутри одной ссылки (design.md, Р2, Р3)
- [x] 1.2 `AnswerMarkdown`: отрисовщик узла `Code` — адрес по Р2
      (`ReturnTo.safe`, без пробелов, вторая буква латинская) и не внутри
      `Link` выводится ссылкой через `withReturn`, прочее — штатным `<code>`;
      javadoc класса дописан
- [x] 1.3 `ProblemDraftQueue`: подпись «Вычитать черновики» (Р4);
      `ProblemIntakeScreenTest` и пример в `assistant/page.html` —
      по новой подписи
- [x] 1.4 `mvn test -Dtest='AnswerMarkdownTest,ProblemIntakeScreenTest,AssistantPageTest'` зелёный

## 2. Правила Помощника

- [x] 2.1 `agent/CLAUDE.md`, «Ответ»: адрес внутри Локуса — только
      Markdown-ссылкой, с примером `[черновик №1](/problems/intake/1)`,
      никогда в обратных кавычках; условие и решение Задачи в чат
      не переписываются, формулы — только на странице вычитки (Р5)
- [x] 2.2 Навык `problem-intake`, раздел 3: по черновику — номер, одна
      строка о задаче без формул, Markdown-ссылка на вычитку, что учителю
      решить; остальные — ссылками строкой ниже; со ссылкой на общее
      правило ответа
- [x] 2.3 `mvn test -Dtest=AgentSkillsNameRegisteredToolsTest` зелёный

## 3. Бэклог и версия

- [ ] 3.1 `mvn clean package` зелёный
- [ ] 3.2 Поднять версию в `pom.xml` до 3.9.0 и дописать в `CHANGELOG.md`
      строку из proposal.md, раздел «Версия»
