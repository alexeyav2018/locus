package ru.locus.work;

import java.time.LocalDate;
import java.util.List;
import ru.locus.assignment.AssignmentId;
import ru.locus.problem.ProblemId;
import ru.locus.user.UserId;

/**
 * Работа — решение одной Задачи одного Задания, принятое Учителем
 * от Ученика: файлы, дата получения, вердикт и примечание
 * (ADR-0014, ADR-0015, ADR-0038).
 *
 * <p>Ученика у Работы нет — он у Задания. Работа существует только
 * на паре «Задание × Задача», и Ученик Задания — единственный, от кого
 * она может быть; вторая колонка с Учеником разошлась бы с первой,
 * и никто не заметил бы. Кому нужен Ученик Работы, тот идёт через
 * Задание.
 *
 * <p>«Не проверена» — это отсутствие вердикта ({@code verdict == null}),
 * а не хранимый флаг: состояние — это данные, а не отметка о них
 * (ADR-0038; тот же довод, что у «не сдано» Задания — инвариант 12
 * domain-model.md). Вердикт ставится и меняется в любой момент после
 * приёма и при приёме не обязателен: учитель переносит снимки с телефона
 * тогда, когда они пришли, а проверяет — когда сядет за стол.
 *
 * <p>Файлов хотя бы один: Работа без скана не существует (ADR-0015).
 * Порядок — порядок загрузки, {@link StudentWorkFile#position}, и запись
 * требует, чтобы список пришёл в этом порядке: перемешанный список
 * ничем себя не выдаст, а страницы решения пойдут вразнобой.
 *
 * <p>Владелец обязателен: Работа — личный контур, и запись без владельца —
 * это запись, которую увидят все (инвариант 11 domain-model.md, ADR-0027).
 *
 * @param verdict {@code null} — Работа не проверена
 * @param note    примечание; {@code null} приводится к пустой строке,
 *                края обрезаются
 * @param files   файлы Работы, 1..n, в порядке {@code position}
 */
public record StudentWork(StudentWorkId id,
                          UserId owner,
                          AssignmentId assignment,
                          ProblemId problem,
                          LocalDate receivedOn,
                          Verdict verdict,
                          String note,
                          List<StudentWorkFile> files) {

    public StudentWork {
        if (owner == null) {
            throw new IllegalArgumentException("У Работы должен быть владелец");
        }
        if (assignment == null) {
            throw new IllegalArgumentException("У Работы должно быть Задание");
        }
        if (problem == null) {
            throw new IllegalArgumentException("У Работы должна быть Задача");
        }
        if (receivedOn == null) {
            throw new IllegalArgumentException("У Работы должна быть дата получения");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Работе нужен хотя бы один файл");
        }
        for (int i = 1; i < files.size(); i++) {
            if (files.get(i).position() <= files.get(i - 1).position()) {
                throw new IllegalArgumentException("Файлы Работы должны идти в порядке загрузки");
            }
        }
        note = note == null ? "" : note.strip();
        files = List.copyOf(files);
    }

    /** Есть ли вердикт; без него Работа показывается как «не проверена». */
    public boolean isChecked() {
        return verdict != null;
    }

    public boolean hasNote() {
        return !note.isEmpty();
    }
}
