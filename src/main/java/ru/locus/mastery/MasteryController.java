package ru.locus.mastery;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.locus.Addresses;
import ru.locus.student.StudentId;

/**
 * Экран Владения одного Ученика (`mastery-views`):
 * {@code GET /mastery?student=} — состояние Ученика целиком
 * ({@link MasteryService#overviewOf}).
 *
 * Своего адреса простановки у отметок нет: с `work-mastery-at-receipt`
 * они приходят в форме приёма Работы или в форме её вердикта и пишутся
 * тем же действием ({@code StudentWorkController}), одной транзакцией.
 * Второй дороги к тому же здесь не держится.
 *
 * Без параметра — к сводке Учеников: вход идёт от Ученика, как в Работы —
 * от Задания. Чужой Ученик — {@link ru.locus.student.StudentNotFoundException}
 * от сервиса, здесь не перехватывается. Контроллер тонкий (standards.md,
 * «Слои и границы»): права и владелец — в {@link MasteryService}.
 */
@Controller
public class MasteryController {

    private final MasteryService mastery;

    public MasteryController(MasteryService mastery) {
        this.mastery = mastery;
    }

    @GetMapping(Addresses.MASTERY)
    public String overview(@RequestParam(required = false) Long student, Model model) {
        if (student == null) {
            return "redirect:" + Addresses.STUDENTS;
        }
        model.addAttribute("overview", mastery.overviewOf(new StudentId(student)));
        return "mastery/student";
    }
}
