package ru.locus.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.locus.IntegrationTest;
import ru.locus.LoggedIn;
import ru.locus.user.Role;

/**
 * Правила дерева: создание, переименование, перемещение и удаление узла.
 *
 * Все проверки стоят в сервисе, поэтому и проверяются на сервисе, а не через
 * экран: правило должно срабатывать при любом способе вызова, включая
 * контроллер, о котором сейчас никто не думает.
 */
class TaxonomyServiceTest extends IntegrationTest {

    @Autowired
    private TaxonomyService taxonomy;

    @Autowired
    private JdbcClient database;

    @BeforeEach
    void logIn() {
        LoggedIn.as(Role.ADMINISTRATOR);
    }

    @AfterEach
    void logOut() {
        LoggedIn.nobody();
    }

    /** Сценарий «Дерево строится на четыре уровня». */
    @Test
    void treeIsBuiltFourLevelsDeep() {
        TaxonomyNodeId first = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId second = taxonomy.create("Уравнения", first);
        TaxonomyNodeId third = taxonomy.create("Квадратные", second);
        TaxonomyNodeId fourth = taxonomy.create("Неполные", third);

        assertThat(taxonomy.node(second).parentNode()).contains(first);
        assertThat(taxonomy.node(third).parentNode()).contains(second);
        assertThat(taxonomy.node(fourth).parentNode()).contains(third);
        assertThat(taxonomy.subtree(first))
                .as("все четыре узла существуют и находятся обходом от корня")
                .extracting(TaxonomyNode::id)
                .containsExactlyInAnyOrder(first, second, third, fourth);
    }

    /** Сценарий «Несколько корней». */
    @Test
    void severalRootsCoexist() {
        TaxonomyNodeId algebra = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId geometry = taxonomy.create(unique("Геометрия"), null);

        assertThat(taxonomy.roots()).extracting(TaxonomyNode::id).contains(algebra, geometry);
        assertThat(taxonomy.tree()).extracting(branch -> branch.node().id()).contains(algebra, geometry);
    }

    /** Сценарий «Узел создан». */
    @Test
    void createdNodeAppearsAmongItsParentsChildren() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);

        TaxonomyNodeId child = taxonomy.create("Уравнения", root);

        assertThat(taxonomy.children(root)).extracting(TaxonomyNode::id).containsExactly(child);
    }

    /** Сценарий «Пустое имя». */
    @Test
    void emptyNameCreatesNothing() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);

        assertThatThrownBy(() -> taxonomy.create("   ", root)).isInstanceOf(IllegalArgumentException.class);

        assertThat(taxonomy.children(root)).isEmpty();
    }

    @Test
    void nameIsTrimmed() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);

        TaxonomyNodeId child = taxonomy.create("  Уравнения  ", root);

        assertThat(taxonomy.node(child).name()).isEqualTo("Уравнения");
    }

    /** Сценарий «Имя повторяется среди братьев». */
    @Test
    void nameTakenBySiblingIsRefusedWithAWordAboutIt() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        taxonomy.create("Уравнения", root);

        assertThatThrownBy(() -> taxonomy.create("Уравнения", root))
                .isInstanceOf(NameAlreadyTakenException.class)
                .hasMessageContaining("занято");

        assertThat(taxonomy.children(root)).hasSize(1);
    }

    /** Сценарий «То же имя под другим родителем». */
    @Test
    void sameNameUnderAnotherParentIsCreated() {
        TaxonomyNodeId triangles = taxonomy.create(unique("Треугольники"), null);
        TaxonomyNodeId circles = taxonomy.create(unique("Окружности"), null);

        taxonomy.create("Признаки подобия", triangles);
        taxonomy.create("Признаки подобия", circles);

        assertThat(taxonomy.children(triangles)).hasSize(1);
        assertThat(taxonomy.children(circles)).hasSize(1);
    }

    /**
     * Сценарий «Приёмником указан узел, перестающий быть Темой» — часть
     * про саму углубляемую Тему.
     *
     * Здесь Тема пуста: проверка приёмника — правило дерева, и о Задачах
     * оно не знает; неверный приёмник отклоняется как неверный ввод, даже
     * когда переезжать нечему. Что при отказе не переезжает и разметка,
     * проверяет {@code ProblemsGuardTheTreeTest}.
     */
    @Test
    void deepenedTopicItselfIsRefusedAsAReceiver() {
        TaxonomyNodeId topic = taxonomy.create(unique("Уравнения"), null);

        assertThatThrownBy(() -> taxonomy.create("Квадратные", topic, TopicReceiver.existing(topic)))
                .isInstanceOf(ReceiverIsNotATopicException.class)
                .hasMessageContaining("Темы");

        assertThat(taxonomy.children(topic)).as("потомок не создан").isEmpty();
    }

    /** Сценарий «Приёмником указан узел, перестающий быть Темой» — Раздел. */
    @Test
    void sectionIsRefusedAsAReceiver() {
        TaxonomyNodeId section = taxonomy.create(unique("Геометрия"), null);
        taxonomy.create("Треугольники", section);
        TaxonomyNodeId topic = taxonomy.create(unique("Уравнения"), null);

        assertThatThrownBy(() -> taxonomy.create("Квадратные", topic, TopicReceiver.existing(section)))
                .isInstanceOf(ReceiverIsNotATopicException.class)
                .hasMessageContaining("потомки");

        assertThat(taxonomy.children(topic)).as("потомок не создан").isEmpty();
    }

    /** Приёмник, которого нет в дереве, — ошибка ввода, а не отказ по существу. */
    @Test
    void unknownReceiverIsAnError() {
        TaxonomyNodeId topic = taxonomy.create(unique("Уравнения"), null);

        assertThatThrownBy(() -> taxonomy.create("Квадратные", topic,
                TopicReceiver.existing(new TaxonomyNodeId(Long.MAX_VALUE))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(taxonomy.children(topic)).as("потомок не создан").isEmpty();
    }

    /** У корня переезжать нечему: приёмник при создании корня — бессмыслица. */
    @Test
    void receiverForARootIsAnError() {
        assertThatThrownBy(() -> taxonomy.create(unique("Алгебра"), null, TopicReceiver.CREATED_CHILD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("корня");
    }

    /** Создаваемый потомок — законный приёмник и на пустой Теме; узел создаётся как обычно. */
    @Test
    void createdChildAsReceiverOnAnEmptyTopicCreatesTheNodeAsUsual() {
        TaxonomyNodeId topic = taxonomy.create(unique("Уравнения"), null);

        TaxonomyNodeId child = taxonomy.create("Квадратные", topic, TopicReceiver.CREATED_CHILD);

        assertThat(taxonomy.children(topic)).extracting(TaxonomyNode::id).containsExactly(child);
        assertThat(taxonomy.node(topic).isSection()).as("прежняя Тема стала Разделом").isTrue();
    }

    /** Сценарий «Узел переименован». */
    @Test
    void renamedNodeKeepsItsPlaceParentAndChildren() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", root);
        TaxonomyNodeId child = taxonomy.create("Квадратные", node);

        taxonomy.rename(node, "Неравенства");

        TaxonomyNode renamed = taxonomy.node(node);
        assertThat(renamed.name()).isEqualTo("Неравенства");
        assertThat(renamed.id()).as("идентификатор узла не меняется").isEqualTo(node);
        assertThat(renamed.parentNode()).contains(root);
        assertThat(taxonomy.children(node)).extracting(TaxonomyNode::id).containsExactly(child);
    }

    /** Сценарий «Новое имя занято братом». */
    @Test
    void renameToASiblingsNameChangesNothing() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        taxonomy.create("Уравнения", root);
        TaxonomyNodeId other = taxonomy.create("Неравенства", root);

        assertThatThrownBy(() -> taxonomy.rename(other, "Уравнения"))
                .isInstanceOf(NameAlreadyTakenException.class);

        assertThat(taxonomy.node(other).name()).isEqualTo("Неравенства");
    }

    @Test
    void renameToItsOwnNameIsNotACollisionWithItself() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", root);

        taxonomy.rename(node, "Уравнения");

        assertThat(taxonomy.node(node).name()).isEqualTo("Уравнения");
    }

    /** Сценарий «Узел перенесён с поддеревом». */
    @Test
    void movedNodeTakesItsSubtreeAlong() {
        TaxonomyNodeId from = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId to = taxonomy.create(unique("Геометрия"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", from);
        TaxonomyNodeId child = taxonomy.create("Квадратные", node);
        TaxonomyNodeId grandchild = taxonomy.create("Неполные", child);

        taxonomy.move(node, to);

        assertThat(taxonomy.children(to)).extracting(TaxonomyNode::id).containsExactly(node);
        assertThat(taxonomy.children(from)).as("прежний родитель узла больше не содержит").isEmpty();
        assertThat(taxonomy.subtree(node))
                .as("поддерево переехало целиком, взаимное расположение прежнее")
                .extracting(TaxonomyNode::id)
                .containsExactlyInAnyOrder(node, child, grandchild);
        assertThat(taxonomy.node(child).parentNode()).contains(node);
    }

    /** Сценарий «Узел поднят в корень». */
    @Test
    void nodeMovedWithoutParentBecomesARoot() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create(unique("Уравнения"), root);

        taxonomy.move(node, null);

        assertThat(taxonomy.node(node).isRoot()).isTrue();
        assertThat(taxonomy.roots()).extracting(TaxonomyNode::id).contains(node);
    }

    /** Сценарий «Перенос внутрь своего поддерева». */
    @Test
    void moveIntoOwnSubtreeIsRefusedAndTreeStaysAsItWas() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", root);
        TaxonomyNodeId child = taxonomy.create("Квадратные", node);
        TaxonomyNodeId grandchild = taxonomy.create("Неполные", child);

        assertThatThrownBy(() -> taxonomy.move(node, grandchild))
                .as("отдалённый потомок — тоже собственное поддерево")
                .isInstanceOf(MoveIntoOwnSubtreeException.class);

        assertThat(taxonomy.node(node).parentNode()).contains(root);
        assertThat(taxonomy.subtree(root))
                .extracting(TaxonomyNode::id)
                .containsExactlyInAnyOrder(root, node, child, grandchild);
    }

    /** Сценарий «Перенос узла под самого себя». */
    @Test
    void moveUnderItselfIsRefused() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", root);

        assertThatThrownBy(() -> taxonomy.move(node, node)).isInstanceOf(MoveIntoOwnSubtreeException.class);

        assertThat(taxonomy.node(node).parentNode()).contains(root);
    }

    /** Сценарий «Имя занято у нового родителя». */
    @Test
    void moveIsRefusedWhenTheNewParentAlreadyHasThatName() {
        TaxonomyNodeId from = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId to = taxonomy.create(unique("Геометрия"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", from);
        taxonomy.create("Уравнения", to);

        assertThatThrownBy(() -> taxonomy.move(node, to)).isInstanceOf(NameAlreadyTakenException.class);

        assertThat(taxonomy.node(node).parentNode()).contains(from);
    }

    /** Сценарий «Пустой узел удалён». */
    @Test
    void emptyNodeIsDeletedAndItsParentStays() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", root);

        taxonomy.delete(node);

        assertThatThrownBy(() -> taxonomy.node(node)).isInstanceOf(IllegalArgumentException.class);
        assertThat(taxonomy.node(root).id()).isEqualTo(root);
    }

    /** Сценарий «Удаление узла с потомками». */
    @Test
    void deletingANodeWithChildrenIsRefusedAndNothingDisappears() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId node = taxonomy.create("Уравнения", root);
        TaxonomyNodeId child = taxonomy.create("Квадратные", node);

        assertThatThrownBy(() -> taxonomy.delete(node))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("потомки");

        assertThat(taxonomy.node(node).id()).isEqualTo(node);
        assertThat(taxonomy.node(child).id()).isEqualTo(child);
    }

    /** Сценарий «Родитель после удаления единственного потомка». */
    @Test
    void parentBecomesATopicWhenItsOnlyChildIsDeleted() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId onlyChild = taxonomy.create("Уравнения", root);
        assertThat(taxonomy.node(root).isSection()).isTrue();

        taxonomy.delete(onlyChild);

        assertThat(taxonomy.node(root).isTopic())
                .as("вид узла ниоткуда не переписывался — он вычисляется")
                .isTrue();
    }

    /**
     * Сценарий «Отметок нет»: отвечающего по существу сегодня нет, и число
     * всегда нулевое. Что оно перестанет быть нулевым не молча, стережёт
     * {@code MasteryRestructureDebtTest}.
     */
    @Test
    void countOfVanishingMarksIsZeroToday() {
        TaxonomyNodeId topic = taxonomy.create(unique("Уравнения"), null);

        assertThat(taxonomy.countVanishingMarks(topic)).isZero();
    }

    /** Снятие с распределением на пустой Теме — обычное снятие: распределять нечего. */
    @Test
    void emptyTopicIsDeletedWithNothingToDistribute() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId topic = taxonomy.create("Уравнения", root);

        taxonomy.deleteWithDistribution(topic, TopicDistribution.NOTHING);

        assertThatThrownBy(() -> taxonomy.node(topic)).isInstanceOf(IllegalArgumentException.class);
        assertThat(taxonomy.node(root).id()).isEqualTo(root);
    }

    /**
     * Сценарий «Задачи поднимаются на родителя» — часть про дерево.
     *
     * Здесь Тема пуста, а распределение называет одного приёмника — родителя:
     * проверка приёмника — правило дерева, и о содержимом оно не знает.
     * Родитель сейчас Раздел, но после снятия единственного потомка станет
     * листом — и проверка, считающая состояние <b>после</b> операции,
     * его пропускает без особого случая. Что при этом переезжают и Задачи,
     * проверяет {@code ProblemsGuardTheTreeTest}.
     */
    @Test
    void parentIsALawfulReceiverWhenTheRemovedTopicWasItsOnlyChild() {
        TaxonomyNodeId parent = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId onlyChild = taxonomy.create("Уравнения", parent);
        assertThat(taxonomy.node(parent).isSection()).isTrue();

        taxonomy.deleteWithDistribution(onlyChild, () -> Set.of(parent));

        assertThatThrownBy(() -> taxonomy.node(onlyChild)).isInstanceOf(IllegalArgumentException.class);
        assertThat(taxonomy.node(parent).isTopic()).as("вид родителя — Тема").isTrue();
    }

    /**
     * Сценарий «Приёмником назначен узел, остающийся Разделом».
     *
     * Родитель с двумя потомками после снятия одного из них остаётся
     * Разделом. Отказ приходит <i>после</i> снятия узла — проверка считает
     * состояние дерева после операции буквально, — и потому здесь же
     * проверяется, что транзакция откатилась: снятый узел на месте.
     */
    @Test
    void receiverKeepingChildrenAfterTheRemovalIsRefusedAndTheTopicStays() {
        TaxonomyNodeId parent = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId topic = taxonomy.create("Уравнения", parent);
        TaxonomyNodeId sibling = taxonomy.create("Неравенства", parent);

        assertThatThrownBy(() -> taxonomy.deleteWithDistribution(topic, () -> Set.of(parent)))
                .isInstanceOf(ReceiverIsNotATopicException.class)
                .hasMessageContaining("потомки")
                .hasMessageContaining("Темы");

        assertThat(taxonomy.node(topic).id()).as("снятие откатилось целиком").isEqualTo(topic);
        assertThat(taxonomy.children(parent)).extracting(TaxonomyNode::id)
                .containsExactlyInAnyOrder(topic, sibling);
    }

    /** Снимаемая Тема — не приёмник для собственного содержимого: после снятия её не будет. */
    @Test
    void removedTopicItselfIsRefusedAsAReceiver() {
        TaxonomyNodeId topic = taxonomy.create(unique("Уравнения"), null);

        assertThatThrownBy(() -> taxonomy.deleteWithDistribution(topic, () -> Set.of(topic)))
                .isInstanceOf(ReceiverIsNotATopicException.class)
                .hasMessageContaining("после снятия");

        assertThat(taxonomy.node(topic).id()).isEqualTo(topic);
    }

    /**
     * Список приёмников для формы снятия считается по дереву после снятия
     * — тем же правилом, что и проверка в {@code deleteWithDistribution}:
     * родитель, у которого снимаемая Тема — единственный потомок, в списке
     * есть, хотя сейчас он Раздел; родитель с другими потомками — нет;
     * снимаемая Тема — нет, хотя сейчас она лист.
     */
    @Test
    void receiversForRemovalAreCountedOnTheTreeAfterTheRemoval() {
        TaxonomyNodeId lonelyParent = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId lonely = taxonomy.create("Уравнения", lonelyParent);
        TaxonomyNodeId crowdedParent = taxonomy.create(unique("Геометрия"), null);
        TaxonomyNodeId crowded = taxonomy.create("Треугольники", crowdedParent);
        TaxonomyNodeId sibling = taxonomy.create("Окружности", crowdedParent);

        List<TaxonomyNodeId> afterLonely = taxonomy.receiverPathsAfterRemoving(lonely).stream()
                .map(TaxonomyPath::id)
                .toList();
        assertThat(afterLonely)
                .as("родитель, остающийся без потомков, — приёмник; снимаемая Тема — нет")
                .contains(lonelyParent, crowded, sibling)
                .doesNotContain(lonely, crowdedParent);

        List<TaxonomyNodeId> afterCrowded = taxonomy.receiverPathsAfterRemoving(crowded).stream()
                .map(TaxonomyPath::id)
                .toList();
        assertThat(afterCrowded)
                .as("родитель, у которого остаётся брат, Разделом и остаётся")
                .contains(lonely, sibling)
                .doesNotContain(crowded, crowdedParent, lonelyParent);
    }

    /** Распределение не снимает узел с потомками: узлы снимаются по одному, снизу вверх. */
    @Test
    void deletionWithDistributionRefusesANodeWithChildren() {
        TaxonomyNodeId node = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId child = taxonomy.create("Уравнения", node);

        assertThatThrownBy(() -> taxonomy.deleteWithDistribution(node, TopicDistribution.NOTHING))
                .isInstanceOf(NodeNotEmptyException.class)
                .hasMessageContaining("потомки");

        assertThat(taxonomy.node(child).id()).isEqualTo(child);
    }

    @Test
    void subtreeOfAnUnknownNodeIsAnError() {
        List<TaxonomyNode> roots = taxonomy.roots();
        long free = roots.stream().mapToLong(node -> node.id().value()).max().orElse(0) + 1_000_000;

        assertThatThrownBy(() -> taxonomy.subtree(new TaxonomyNodeId(free)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не существует");
    }

    @Test
    void pathsNameEveryNodeFromTheRootDown() {
        String algebra = unique("Алгебра");
        TaxonomyNodeId root = taxonomy.create(algebra, null);
        TaxonomyNodeId child = taxonomy.create("Уравнения", root);

        assertThat(taxonomy.paths())
                .as("путь отличает одинаковые имена под разными родителями")
                .contains(new TaxonomyPath(child, algebra + " / Уравнения"));
    }

    @Test
    void pathOfANodeIsBuiltFromTheRootDown() {
        String algebra = unique("Алгебра");
        TaxonomyNodeId root = taxonomy.create(algebra, null);
        TaxonomyNodeId child = taxonomy.create("Уравнения", root);
        TaxonomyNodeId grandchild = taxonomy.create("Квадратные", child);

        assertThat(taxonomy.path(grandchild).path()).isEqualTo(algebra + " / Уравнения / Квадратные");
        assertThat(taxonomy.path(root).path()).as("у корня путь — он сам").isEqualTo(algebra);
    }

    /** Сценарий «Цепочка предков глубокого узла». */
    @Test
    void ancestryNamesEveryNodeFromTheRootDownToTheOneAsked() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId second = taxonomy.create("Уравнения", root);
        TaxonomyNodeId third = taxonomy.create("Квадратные", second);
        TaxonomyNodeId fourth = taxonomy.create("Неполные", third);

        assertThat(taxonomy.ancestry(fourth))
                .as("цепочка идёт от корня к запрошенному узлу и включает его самого")
                .extracting(TaxonomyNode::id)
                .containsExactly(root, second, third, fourth);
    }

    /** Сценарий «Цепочка предков корня». */
    @Test
    void ancestryOfARootIsTheRootAlone() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);

        assertThat(taxonomy.ancestry(root))
                .extracting(TaxonomyNode::id)
                .containsExactly(root);
    }

    @Test
    void ancestryOfAnUnknownNodeIsAnError() {
        List<TaxonomyNode> roots = taxonomy.roots();
        long free = roots.stream().mapToLong(node -> node.id().value()).max().orElse(0) + 1_000_000;

        assertThatThrownBy(() -> taxonomy.ancestry(new TaxonomyNodeId(free)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не существует");
    }

    /**
     * Цикл в дереве сервис создать не даёт, поэтому испорченные данные
     * подкладываются мимо него — прямо в таблицу. Без предела шагов подъём
     * по такому дереву не кончился бы никогда: тест ждёт ошибки, а сам
     * его провал выглядел бы как зависшая сборка.
     */
    @Test
    void ancestryOverACycleInTheDataFailsInsteadOfSpinningForever() {
        TaxonomyNodeId root = taxonomy.create(unique("Алгебра"), null);
        TaxonomyNodeId child = taxonomy.create("Уравнения", root);
        parentInDatabase(root, child);
        try {
            assertThatThrownBy(() -> taxonomy.ancestry(child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("предела глубины");
        } finally {
            parentInDatabase(root, null);
        }
    }

    /** Ставит родителя мимо сервиса — так, как сервис поставить не позволил бы. */
    private void parentInDatabase(TaxonomyNodeId id, TaxonomyNodeId parent) {
        database.sql("update taxonomy_node set parent_id = ? where id = ?")
                .params(parent == null ? null : parent.value(), id.value())
                .update();
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID();
    }
}
