package ru.locus.assignment;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import ru.locus.problem.ProblemId;

/**
 * Прежняя выдача Задачи вошедшим Учителем — то, что форма выдачи называет
 * подсказкой «уже выдана» (design.md изменения {@code assignment-repeat-hint}).
 *
 * <p>Адресат — текстом, как его показывают: у выдачи Раздачей это имя
 * Группы на момент выдачи (Раздача помнит его сама, Группа удаляется
 * в любой момент — ADR-0035), у личной — имя Ученика. Выдача Раздачей
 * здесь одна, сколько бы Заданий в ней ни было: Учитель выдавал Группе
 * одним действием и помнит это одним действием.
 *
 * <p>Запись только перевозит прочитанное до экрана; выдачу она ничем
 * не ограничивает — повтор той же Задачи тому же адресату проходит.
 *
 * @param byBatch {@code true} — выдана Группе Раздачей, {@code false} —
 *                Ученику лично
 */
public record PriorIssue(ProblemId problem, LocalDate issuedOn, String addressee, boolean byBatch) {

    public PriorIssue {
        if (problem == null || issuedOn == null) {
            throw new IllegalArgumentException("У прежней выдачи должны быть Задача и дата");
        }
        if (addressee == null || addressee.isBlank()) {
            throw new IllegalArgumentException("У прежней выдачи должен быть назван адресат");
        }
    }

    /**
     * Выдача, как её называет строка «уже выдана»: Группа — в кавычках «»,
     * как в сводке Заданий, Ученик — без них, затем дата выдачи. Кавычки
     * различают адресатов без слов «Группе»/«Ученику» и без склонения имён.
     */
    public String label() {
        return (byBatch ? "«" + addressee + "»" : addressee) + " — " + SHOWN.format(issuedOn);
    }

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd.MM.yyyy");
}
