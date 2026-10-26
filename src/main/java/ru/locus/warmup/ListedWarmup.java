package ru.locus.warmup;

import java.util.List;
import ru.locus.student.Student;

/**
 * Разминка, названная по-человечески: адресат словом и участники
 * с именами — для списка и для страницы Разминки.
 *
 * <p>Адресат — имя Группы на момент заведения, а у Разминки Ученику — имя
 * единственного участника. Участники — Ученики целиком, с признаком
 * «выбыл»: выбывший после Разминки остаётся её участником и помечен
 * (ADR-0040). Порядок участников — как у {@link Warmup#students()}.
 *
 * @param addressee    имя Группы или Ученика — как Разминка названа человеку
 * @param participants участники в порядке {@link Warmup#students()}
 */
public record ListedWarmup(Warmup warmup, String addressee, List<Student> participants) {

    public ListedWarmup {
        if (warmup == null) {
            throw new IllegalArgumentException("В списке не бывает пустой Разминки");
        }
        if (addressee == null || addressee.isBlank()) {
            throw new IllegalArgumentException("У Разминки в списке должен быть назван адресат");
        }
        participants = List.copyOf(participants);
    }
}
