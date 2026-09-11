package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Задача 1.3: долг отметок Владения в перестройке рубрикатора записан там,
 * где его найдут.
 *
 * Перестройка спрашивает у чужих областей два новых вопроса — «перевесь своё
 * на приёмник» и «сколько твоего исчезнет вместе с узлом». По существу
 * на второй обязана отвечать работа {@code mastery-marks}: отметка, снятая
 * вместе с Темой, невосполнима, а слиянию она не подлежит (ADR-0007).
 * Сегодня ответчика нет, и предупреждение Администратору всегда говорит
 * «терять нечего».
 *
 * <b>Это и есть опасность.</b> Пустое предупреждение выглядит ровно так же
 * убедительно, как настоящее: Администратор видит «исчезающих отметок нет»,
 * подтверждает снятие — и узнаёт правду по пропавшим суждениям, через
 * полгода и без возможности их вернуть. Обычная сборка такого не ловит:
 * значение по умолчанию законно компилируется и законно возвращает ноль.
 *
 * Поэтому проверяется сам исходный текст — по образцу
 * {@code ProblemUsageDebtTest} и {@code DeletionCheckDebtTest}. Тест держит
 * три вещи: вопросы объявлены, пояснение называет работу, обязанную
 * ответить, и сегодня по существу не отвечает никто. Последнее — срок
 * долга: появится реализация, тест упадёт и потребует подтвердить, что счёт
 * настоящий, а не унаследованный ноль.
 *
 * Контекст приложения не поднимается намеренно. Реализации искались бы
 * в нём списком бобов, но боб может оказаться прокси, у которого
 * переопределено всё подряд, — и «отвечает по существу» стало бы
 * неотличимо от «завёрнут Spring'ом».
 */
class MasteryRestructureDebtTest {

    private static final Path SOURCES = Path.of("src/main/java");

    @Test
    void theQuestionsAreDeclaredAndNameTheWorkThatMustAnswer() throws IOException {
        String question = sourceOf(NodeContent.class);

        assertThat(question)
                .as("переезд содержимого на Тему-приёмник спрашивается у чужих областей")
                .contains("moveTopicContent");
        assertThat(question)
                .as("счёт исчезающего невосполнимо спрашивается у чужих областей")
                .contains("countVanishing");
        assertThat(question)
                .as("отвечать по существу обязана работа отметок Владения")
                .contains("mastery-marks");
        assertThat(question)
                .as("пояснение называет ADR, где решение обосновано")
                .contains("ADR-0007");
    }

    /**
     * Счёт идёт по всем учителям и по владельцу не фильтруется — единственное
     * во всей системе законное исключение, и оно обязано быть названным.
     * Исключение, о котором в коде не сказано, через полгода читается как
     * забытый фильтр и «чинится» кем-нибудь из лучших побуждений.
     */
    @Test
    void theAbsenceOfTheOwnerFilterIsNamedAsDeliberate() throws IOException {
        String question = sourceOf(NodeContent.class);

        assertThat(question)
                .as("отсутствие фильтра по владельцу названо намеренным, со ссылкой на решение")
                .contains("ADR-0027");
        assertThat(question)
                .as("наружу отдаётся только число — ни ученика, ни учителя, ни значения")
                .contains("только число");
    }

    /**
     * Срок долга. Пока ни одна область не отвечает по существу, счёт
     * тождественно нулевой, а предупреждение Администратору — пустое.
     */
    @Test
    void nobodyAnswersTheCountYet() throws IOException {
        List<Path> implementations = implementationsOfNodeContent();

        assertThat(implementations)
                .as("реализации вопроса есть — Задачи и Теория уже отвечают на «что лежит на узле»")
                .isNotEmpty();

        List<String> answering = implementations.stream()
                .filter(MasteryRestructureDebtTest::answersTheCount)
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(answering)
                .as("""
                        Появился ответчик на countVanishing: %s.
                        Если это отметки Владения — долг погашен, и тест пора переписать \
                        на проверку настоящего счёта: он обязан считать отметки всех учеников \
                        всех учителей и не фильтроваться по владельцу (ADR-0007, ADR-0027). \
                        Если это кто-то другой — проверьте, точно ли его содержимое исчезает \
                        невосполнимо: Задачи, например, не исчезают, а распределяются \
                        по приёмникам.""", answering)
                .isEmpty();
    }

    /** Исходники классов, объявляющих себя реализациями вопроса. */
    private static List<Path> implementationsOfNodeContent() throws IOException {
        try (Stream<Path> tree = Files.walk(SOURCES)) {
            return tree.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("NodeContent.java"))
                    .filter(path -> read(path).contains("implements NodeContent"))
                    .toList();
        }
    }

    /**
     * Отвечает ли эта реализация на счёт по существу.
     *
     * Спрашивается об объявлении метода, а не о возвращённом значении:
     * реализация, честно считающая и вернувшая сегодня ноль, — это погашенный
     * долг, а не отсутствующий, и по числу их не различить.
     *
     * Ищется именно сигнатура, а не имя: упоминать вопрос в пояснении —
     * законно и даже полезно ({@code TheoryOnNode} объясняет там, почему
     * не отвечает), и принять такое упоминание за ответ значило бы снять
     * долг, которого никто не гасил.
     */
    private static boolean answersTheCount(Path implementation) {
        return read(implementation).contains("int countVanishing(");
    }

    private static String sourceOf(Class<?> type) throws IOException {
        Path source = SOURCES.resolve(type.getName().replace('.', '/') + ".java");
        assertThat(source).as("исходный текст %s найден", type.getSimpleName()).exists();
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Не прочитать исходник " + path, unreadable);
        }
    }
}
