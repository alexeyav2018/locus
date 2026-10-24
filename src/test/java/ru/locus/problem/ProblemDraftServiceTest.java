package ru.locus.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.TestAccounts;
import ru.locus.TestLibrary;
import ru.locus.dictionary.SolutionMethodId;
import ru.locus.taxonomy.TaxonomyNodeId;
import ru.locus.user.Role;

/**
 * Задача 3.4 problem-intake: запись Черновика Задачи с проверками и версией,
 * утверждение Задачей с текстом и снимком как есть, отказ заведения,
 * держание общего фото двумя черновиками и права (design.md, Р2–Р5).
 */
class ProblemDraftServiceTest extends IntegrationTest {

    private static final String CONDITION = "Найдите $x$, если $\\log_2 ⟦8⟧ = x$.";
    private static final String SOLUTION = "По определению $x = 3$.";

    @Autowired
    private ProblemDraftService service;

    @Autowired
    private ProblemDraftRepository repository;

    @Autowired
    private ProblemService problems;

    @Autowired
    private AssemblyDraftService assemblyDrafts;

    @Autowired
    private AssemblyDraftRepository assemblyRepository;

    @Autowired
    private TestLibrary library;

    @Autowired
    private TestAccounts accounts;

    private TestAccounts.Account administrator;

    @BeforeEach
    void administratorLoggedIn() {
        administrator = accounts.settled(Role.ADMINISTRATOR);
        LoggedIn.as(administrator);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        LoggedIn.nobody();
    }

    @Test
    void createdDraftStartsAtVersionZeroMarkedByItsEditor() throws IOException {
        ProblemDraft draft = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);

        assertThat(draft.version()).isZero();
        assertThat(draft.changedBy()).isEqualTo(ProblemDraft.Editor.ASSISTANT);
        assertThat(draft.content().conditionText()).isEqualTo(CONDITION);
        assertThat(service.drafts()).extracting(ProblemDraft::id).containsExactly(draft.id());
    }

    @Test
    void writeChangesOnlyTheNamedPartAndRaisesTheVersion() throws IOException {
        ProblemDraft draft = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);

        ProblemDraft written = service.write(draft.id(), draft.version(),
                change(null, "Исправленное решение $x = 3$."), ProblemDraft.Editor.OWNER);

        assertThat(written.version()).isEqualTo(draft.version() + 1);
        assertThat(written.changedBy()).isEqualTo(ProblemDraft.Editor.OWNER);
        assertThat(written.content().solutionText()).isEqualTo("Исправленное решение $x = 3$.");
        assertThat(written.content().conditionText()).as("не названное — прежнее").isEqualTo(CONDITION);
    }

    /** Сценарий «Раздел вместо Темы». */
    @Test
    void sectionInTopicsIsRefusedAndTheDraftStaysAsItWas() throws IOException {
        ProblemDraft draft = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);

        assertThatThrownBy(() -> service.write(draft.id(), draft.version(),
                topics(List.of(library.section())), ProblemDraft.Editor.OWNER))
                .isInstanceOf(NotATopicException.class);

        assertThat(service.draft(draft.id())).isEqualTo(draft);
    }

    @Test
    void missingMethodIsRefused() throws IOException {
        ProblemDraft draft = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);

        assertThatThrownBy(() -> service.write(draft.id(), draft.version(),
                methods(List.of(new SolutionMethodId(Integer.MAX_VALUE))), ProblemDraft.Editor.OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Метода");
        assertThat(service.draft(draft.id())).isEqualTo(draft);
    }

    @Test
    void lineOutsideTheAssemblyDraftIsRefused() throws IOException {
        AssemblyDraft book = assemblyDrafts.upload("сборник.pdf",
                new ByteArrayInputStream(AssemblyDraftServiceTest.pdf(3)));
        PdfAssemblyOrder outside = new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(book.id(), 2, 5)));

        assertThatThrownBy(() -> service.create(new ProblemDraftContent(null, CONDITION, SOLUTION, outside,
                null, null, null, null, null, null), ProblemDraft.Editor.ASSISTANT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сборник.pdf");
        assertThat(service.drafts()).isEmpty();
    }

    /** Сценарий «Правка со старой версией». */
    @Test
    void writeWithAStaleVersionIsRefusedAndTheDraftKeepsTheNewerWrite() throws IOException {
        ProblemDraft read = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);
        ProblemDraft assistant = service.write(read.id(), read.version(),
                change("Условие Помощника", null), ProblemDraft.Editor.ASSISTANT);

        assertThatThrownBy(() -> service.write(read.id(), read.version(),
                change("Условие учителя", null), ProblemDraft.Editor.OWNER))
                .isInstanceOf(ProblemDraftChangedException.class)
                .hasMessageContaining("перечитайте");
        assertThat(service.draft(read.id())).isEqualTo(assistant);
        assertThatThrownBy(() -> service.save(read.id(), read.version(), ProblemDraftService.Kind.TEXT))
                .isInstanceOf(ProblemDraftChangedException.class);
        assertThatThrownBy(() -> service.delete(read.id(), read.version()))
                .isInstanceOf(ProblemDraftChangedException.class);
        assertThat(service.draft(read.id())).isEqualTo(assistant);
    }

    /** Сценарии «Сохранение с текущей версией» и «Пометки не попадают в Задачу». */
    @Test
    void savedAsTextTheDraftBecomesAProblemWithTextWithoutMarksAndWithEvidence() throws IOException {
        AssemblyDraft photo = photo();
        ProblemDraft draft = service.create(content(photo), ProblemDraft.Editor.ASSISTANT);

        ProblemId id = service.save(draft.id(), draft.version(), ProblemDraftService.Kind.TEXT);

        Problem problem = problems.problem(id);
        assertThat(problem.hasText()).isTrue();
        assertThat(problem.text().condition()).isEqualTo("Найдите $x$, если $\\log_2 8 = x$.");
        assertThat(problem.text().conditionEvidence()).as("Улика условия собрана из строк").isNotNull();
        assertThat(problem.text().solutionEvidence()).as("у решения строк нет — и Улики нет").isNull();
        assertThat(repository.find(administrator.id(), draft.id())).as("черновик закрыт").isEmpty();
        assertThat(exists(photo)).as("последний черновик забирает исходник").isFalse();
    }

    /** Сценарий «Снимок как есть». */
    @Test
    void savedAsSnapshotTheDraftBecomesAProblemWithoutText() throws IOException {
        AssemblyDraft photo = photo();
        PdfAssemblyOrder piece = whole(photo);
        ProblemDraft draft = service.create(new ProblemDraftContent(ExamPart.SECOND, CONDITION, SOLUTION,
                piece, piece, List.of(), List.of(library.topic()), List.of(library.method()), List.of(),
                List.of()), ProblemDraft.Editor.ASSISTANT);

        ProblemId id = service.save(draft.id(), draft.version(), ProblemDraftService.Kind.SNAPSHOT);

        assertThat(problems.problem(id).hasText()).isFalse();
        assertThat(repository.find(administrator.id(), draft.id())).isEmpty();
    }

    /** Сценарий «Отказ разметкой» на уровне сервиса: отказ заведения откатывает удаление черновика. */
    @Test
    void refusedProblemLeavesTheDraftAndItsAssemblyDraft() throws IOException {
        AssemblyDraft photo = photo();
        ProblemDraft draft = service.create(content(photo), ProblemDraft.Editor.ASSISTANT);
        ProblemDraft withoutMethods = service.write(draft.id(), draft.version(), methods(List.of()),
                ProblemDraft.Editor.OWNER);

        assertThatThrownBy(() -> service.save(withoutMethods.id(), withoutMethods.version(),
                ProblemDraftService.Kind.TEXT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.draft(draft.id())).isEqualTo(withoutMethods);
        assertThat(exists(photo)).isTrue();
    }

    /** Сценарии «Вторая Задача с того же листа» и «Последняя Задача забирает исходник». */
    @Test
    void twoDraftsOfOnePhotoBothBecomeProblemsAndTheLastTakesThePhoto() throws IOException {
        AssemblyDraft photo = photo();
        ProblemDraft first = service.create(content(photo), ProblemDraft.Editor.ASSISTANT);
        ProblemDraft second = service.create(content(photo), ProblemDraft.Editor.ASSISTANT);

        service.save(first.id(), first.version(), ProblemDraftService.Kind.TEXT);

        assertThat(exists(photo)).as("второй черновик держит фото").isTrue();
        assertThat(service.evidence(service.draft(second.id())).condition()).hasSize(1);
        ProblemId secondProblem = service.save(second.id(), second.version(), ProblemDraftService.Kind.TEXT);
        assertThat(problems.problem(secondProblem).text().conditionEvidence()).isNotNull();
        assertThat(exists(photo)).isFalse();
    }

    @Test
    void deleteRemovesTheDraftAndMakesNoProblem() throws IOException {
        ProblemDraft draft = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);

        service.delete(draft.id(), draft.version());

        assertThatThrownBy(() -> service.draft(draft.id())).isInstanceOf(ProblemDraftNotFoundException.class);
    }

    /** Сценарии «Подсветка неразборчивого» и «Ошибка в формуле черновика»: отрисовка без пометок, отказ на месте. */
    @Test
    void renderingStripsMarksAndRefusesABrokenFormulaInPlace() throws IOException {
        ProblemDraft draft = service.create(content(photo()), ProblemDraft.Editor.ASSISTANT);

        TextPreview rendered = service.rendering(draft);
        assertThat(rendered.refusal()).isNull();
        assertThat(rendered.condition()).isNotEmpty();
        assertThat(service.evidence(draft).condition()).hasSize(1);
        assertThat(service.evidence(draft).solution()).isEmpty();

        ProblemDraft broken = service.write(draft.id(), draft.version(), change("Формула $\\foo$", null),
                ProblemDraft.Editor.OWNER);
        assertThat(service.rendering(broken).refusal()).contains("\\foo");
    }

    @Test
    void teacherWithoutTheAdministratorRoleIsRefused() {
        LoggedIn.as(accounts.settled(Role.TEACHER));

        assertThatThrownBy(() -> service.drafts()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(new ProblemDraftContent(null, CONDITION, SOLUTION, null, null,
                null, null, null, null, null), ProblemDraft.Editor.OWNER))
                .isInstanceOf(AccessDeniedException.class);
    }

    private ProblemDraftContent content(AssemblyDraft photo) {
        return new ProblemDraftContent(ExamPart.SECOND, CONDITION, SOLUTION, whole(photo), null,
                List.of("8 похоже на 9"), List.of(library.topic()), List.of(library.method()), List.of(),
                List.of("Метод интервалов"));
    }

    private static PdfAssemblyOrder whole(AssemblyDraft photo) {
        return new PdfAssemblyOrder(List.of(new PdfAssemblyOrder.Line(photo.id(), 1, 1)));
    }

    private static ProblemDraftChange change(String conditionText, String solutionText) {
        return new ProblemDraftChange(null, conditionText, solutionText, null, null, null, null, null, null, null);
    }

    private static ProblemDraftChange topics(List<TaxonomyNodeId> topics) {
        return new ProblemDraftChange(null, null, null, null, null, null, topics, null, null, null);
    }

    private static ProblemDraftChange methods(List<SolutionMethodId> methods) {
        return new ProblemDraftChange(null, null, null, null, null, null, null, methods, null, null);
    }

    private AssemblyDraft photo() throws IOException {
        return assemblyDrafts.upload("лист.png", new ByteArrayInputStream(AssemblyDraftServiceTest.png()));
    }

    private boolean exists(AssemblyDraft draft) {
        return !assemblyRepository.findByIds(administrator.id(), List.of(draft.id())).isEmpty();
    }
}
