package ru.locus.problem;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.locus.dictionary.CharacteristicId;
import ru.locus.dictionary.DictionaryUsage;
import ru.locus.dictionary.SolutionMethodId;

/**
 * Ответ разметки Задач на вопрос словарей «употреблена ли эта запись».
 *
 * Этим долг, записанный при построении словарей, погашен: до появления Задач
 * ссылаться на запись было нечему, и проверка удаления выполнялась
 * тождественно. Теперь Метод, которым размечена хотя бы одна Задача,
 * и Характеристика, указанная хотя бы у одной, не удаляются.
 *
 * Оставшийся долг словарей — отметки Владения ({@code mastery-marks}): их
 * ячейки опираются на Метод, и они добавят свою реализацию
 * {@link DictionaryUsage}, не трогая ни эту, ни сервисы словарей.
 */
@Component
public class ProblemMarkupUsage implements DictionaryUsage {

    private final ProblemRepository problems;

    public ProblemMarkupUsage(ProblemRepository problems) {
        this.problems = problems;
    }

    @Override
    public Optional<String> ofMethod(SolutionMethodId method) {
        int count = problems.countByMethod(method);
        return count == 0 ? Optional.empty() : Optional.of("им размечены Задачи (" + count + ")");
    }

    @Override
    public Optional<String> ofCharacteristic(CharacteristicId characteristic) {
        int count = problems.countByCharacteristic(characteristic);
        return count == 0 ? Optional.empty() : Optional.of("она указана у Задач (" + count + ")");
    }
}
