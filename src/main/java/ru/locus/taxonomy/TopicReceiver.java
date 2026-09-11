package ru.locus.taxonomy;

/**
 * Тема-приёмник — узел, на который переезжает содержимое углубляемой Темы
 * (ADR-0007).
 *
 * Отдельный тип, а не идентификатор: самый частый приёмник — создаваемый
 * потомок, а у него идентификатора ещё нет, он появится только внутри
 * операции (design.md, «Приёмник по умолчанию — создаваемый потомок»).
 * Выражать его «особым» значением идентификатора — приглашение к тихой
 * ошибке: такое значение однажды уйдёт в запрос как настоящее.
 *
 * Приёмника выбирает Администратор, а не назначает система: ни одного узла
 * дерево по своей воле не заводит.
 */
public sealed interface TopicReceiver {

    /** Создаваемый потомок: узел, которого до операции не существует. */
    record CreatedChild() implements TopicReceiver {
    }

    /** Существующий узел дерева, указанный идентификатором. */
    record Existing(TaxonomyNodeId id) implements TopicReceiver {
        public Existing {
            if (id == null) {
                throw new IllegalArgumentException("Приёмник должен быть указан");
            }
        }
    }

    TopicReceiver CREATED_CHILD = new CreatedChild();

    static TopicReceiver existing(TaxonomyNodeId id) {
        return new Existing(id);
    }
}
