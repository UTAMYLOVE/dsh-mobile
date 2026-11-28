package app.agency.core.lessons

import app.agency.core.rules.StudyRule

/** Quiz validity and grading, all in code: a model writes the questions, never the verdict. */
object QuizCheck {
    const val MIN_QUOTE = 8

    /** Strips every kind of whitespace so line breaks and spacing in the source do not break a quote match. */
    fun normalise(s: String): String = s.filterNot { it.isWhitespace() || it == '　' || it == ' ' }

    fun questionCount(study: StudyRule): Int = if (study.quizQuestions > 0) study.quizQuestions else 3

    /** What is wrong with [quiz]; empty means valid. Kept as text so a retry prompt can say what to fix. */
    fun problems(quiz: Quiz, material: String, study: StudyRule): List<String> {
        val out = ArrayList<String>()
        val want = questionCount(study)
        if (quiz.questions.size != want) out += "題數要剛好 $want 題，目前是 ${quiz.questions.size} 題"
        val mat = normalise(material)
        val seen = HashSet<String>()
        quiz.questions.forEachIndexed { i, q ->
            val n = i + 1
            if (q.q.isBlank()) out += "第 $n 題沒有題目"
            if (!seen.add(normalise(q.q))) out += "第 $n 題和別題重複"
            val opts = q.options.map { normalise(it) }
            if (q.options.size != 4) out += "第 $n 題要有四個選項"
            if (opts.any { it.isEmpty() }) out += "第 $n 題有空的選項"
            if (opts.toSet().size != opts.size) out += "第 $n 題的選項有重複"
            if (q.answer !in q.options.indices) out += "第 $n 題的答案編號不對"
            val quote = normalise(q.quote)
            if (quote.length < MIN_QUOTE) out += "第 $n 題的引文太短"
            else if (!mat.contains(quote)) out += "第 $n 題的引文不在教材裡"
        }
        return out
    }

    fun valid(quiz: Quiz, material: String, study: StudyRule): Boolean = problems(quiz, material, study).isEmpty()

    /** Number of right answers; a missing or out-of-range answer is wrong. */
    fun grade(quiz: Quiz, answers: List<Int>): Int =
        quiz.questions.indices.count { i -> answers.getOrNull(i) == quiz.questions[i].answer }

    /** Right answers needed: `quizPass`, at least one, never more than the number of questions. */
    fun passMark(study: StudyRule, total: Int): Int = study.quizPass.coerceAtLeast(1).coerceAtMost(total.coerceAtLeast(1))
}
