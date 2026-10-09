## 1. Подписи полей файлов

- [x] 1.1 Тест в `ProblemScreenTest`: формы заведения и правки не содержат «PDF условия»/«PDF решения» и «Оба PDF», содержат подписи «Условие», «Решение», «Другое условие», «Новое решение»; красный до правки
- [x] 1.2 `problem/form.html`: подписи полей и блоков замены и подсказка — по `proposal.md`
- [x] 1.3 `mvn test -Dtest='Problem*Test,Assembly*Test'` зелёный
- [x] 1.4 `mvn clean package` зелёный; `grep -n "PDF условия\|PDF решения" src/main/resources/templates/problem/form.html` пуст; документы `openspec/context/` и `CLAUDE.md` не затрагиваются
- [x] 1.5 Поднять версию в `pom.xml` до 3.0.2 и дописать в `CHANGELOG.md` строку из `proposal.md`, раздел «Версия»
