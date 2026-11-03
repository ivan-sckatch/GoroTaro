package ru.gorotaro.domain

/** Synthesis, practical action and optional reflection have separate editorial roles. */
internal object NarrativeComposer {
    fun action(spread: Spread, cards: List<Card>, orientations: List<Orientation>, n: List<Narrative>): String {
        val step = cards.last().action(orientations.last())
        if (spread == Spread.DAY) return step
        val limit = when (n[1].pace) {
            NarrativePace.MOVE -> "Ограничьтесь одним посильным шагом."
            NarrativePace.PAUSE -> "Оставьте время проверить результат."
            NarrativePace.STRUCTURE -> "Заранее обозначьте границы этой пробы."
            NarrativePace.CONNECT -> "Уточните согласие другого участника, если шаг его затрагивает."
            NarrativePace.CHANGE -> "Сначала попробуйте в небольшом масштабе."
        }
        return "$step $limit"
    }

    private fun relation(a: Narrative, b: Narrative): String = when {
        a.pace == NarrativePace.MOVE && b.pace == NarrativePace.PAUSE ->
            "Активность здесь встречается с потребностью в паузе: темп решения стоит согласовать с ясностью условий."
        a.pace == NarrativePace.PAUSE && b.pace == NarrativePace.MOVE ->
            "Размышление и действие дополняют друг друга: понимание стоит закрепить посильным шагом, сохраняя время для проверки."
        setOf(a.pace, b.pace) == setOf(NarrativePace.STRUCTURE, NarrativePace.CHANGE) ->
            "Между этими картами возникает напряжение устойчивости и перемен: полезно сохранить надёжную основу, пересматривая то, что перестало работать."
        a.pace == NarrativePace.CONNECT && b.pace == NarrativePace.STRUCTURE || a.pace == NarrativePace.STRUCTURE && b.pace == NarrativePace.CONNECT ->
            "Здесь забота и договорённости поддерживают друг друга: ясные границы помогают сохранить взаимность без лишнего давления."
        a.pace == NarrativePace.CHANGE && b.pace == NarrativePace.PAUSE || a.pace == NarrativePace.PAUSE && b.pace == NarrativePace.CHANGE ->
            "Перемены и переосмысление связаны: пауза может помочь выбрать, что сохранить, а что постепенно отпустить."
        a.pace == NarrativePace.MOVE && b.pace == NarrativePace.CONNECT ->
            "Инициативу важно поддержать контактом: самостоятельное действие становится устойчивее, когда остаётся место для обратной связи и взаимности."
        a.pace == NarrativePace.CONNECT && b.pace == NarrativePace.MOVE ->
            "Контакт получает продолжение в действии: ясный шаг помогает закрепить договорённость и сделать поддержку ощутимой."
        setOf(a.pace, b.pace) == setOf(NarrativePace.MOVE, NarrativePace.STRUCTURE) ->
            "Энергии действия нужна опора в ясных условиях: правила и распределение ответственности помогут довести начатое до результата."
        setOf(a.pace, b.pace) == setOf(NarrativePace.CONNECT, NarrativePace.PAUSE) ->
            "Личные размышления и контакт требуют равновесия: время на себя не должно исключать взаимную поддержку."
        setOf(a.pace, b.pace) == setOf(NarrativePace.MOVE, NarrativePace.CHANGE) ->
            "Действие и перемены связаны готовностью менять способ: продолжайте движение, проверяя, подходит ли прежний план новым условиям."
        setOf(a.pace, b.pace) == setOf(NarrativePace.CONNECT, NarrativePace.CHANGE) ->
            "Перемены затрагивают и отношения: обновляя условия, сохраняйте уважение к потребностям и границам участников."
        setOf(a.pace, b.pace) == setOf(NarrativePace.STRUCTURE, NarrativePace.PAUSE) ->
            "Правила полезно проверить в спокойной паузе: так легче отличить надёжную опору от привычки, которая больше не помогает."
        a.pace == b.pace -> when (a.pace) {
            NarrativePace.MOVE -> "Обе карты усиливают тему действия; полезно собрать энергию в одном направлении, сохраняя реалистичный темп."
            NarrativePace.PAUSE -> "Обе карты подчёркивают необходимость осмысления; чтобы пауза не стала застоем, определите, какой вопрос она должна прояснить."
            NarrativePace.STRUCTURE -> "Обе карты связывают устойчивость с ясными условиями; договорённости должны поддерживать результат, оставляя место для гибкости."
            NarrativePace.CONNECT -> "В обеих картах важна взаимность; забота и сотрудничество устойчивы, когда потребности участников названы прямо."
            NarrativePace.CHANGE -> "Обе карты выделяют переход; сначала определите, что уже исчерпано, и сохраните опоры, которые пригодятся на новом этапе."
        }
        else -> error("Не описана связь характеров действия")
    }

    fun compose(spread: Spread, cards: List<Card>, orientations: List<Orientation>, n: List<Narrative>, text: List<String>, variant: Int, topic: Topic = Topic.GENERAL): String {
        fun card(i: Int) = "«${cards[i].nameRu}»" + if (orientations[i] == Orientation.REVERSED) " · перевёрнутая" else ""
        if (spread == Spread.DAY) return text[0]
        val opening = listOf("Основная задача", "Ключевая тема", "Главный ориентир")[variant]
        val context = when (topic) {
            Topic.GENERAL -> "ситуации"
            Topic.COMMUNICATION -> "общения"
            Topic.WORK -> "работы"
        }
        val roles = when (spread) {
            Spread.SITUATION -> "$opening $context — ${n[0].goal} (${card(0)}). Возможное осложнение (${card(1)}) — ${n[1].risk}. Ресурс (${card(2)}) помогает ${n[2].goal}."
            Spread.WEEK -> "$opening недели — ${n[0].goal} (${card(0)}). Препятствие (${card(1)}) — ${n[1].risk}. Полезное направление (${card(2)}) — ${n[2].goal}."
            Spread.GENERAL -> "$opening в прошлом — ${n[0].goal} (${card(0)}). В настоящем (${card(1)}) важно учитывать, что ${n[1].risk}. Возможное продолжение (${card(2)}) — ${n[2].goal}."
            Spread.DECISION -> "$opening пути «{optionA}» — ${n[0].goal} (${card(0)}). Для «{optionB}» ресурс — ${n[1].goal}, а ограничение — ${n[1].risk} (${card(1)}). Общая проверка (${card(2)}) — возможность ${n[2].goal}."
            Spread.DAY -> error("Одна карта не требует связки")
        }
        val focus = if (spread != Spread.SITUATION) "" else when (topic) {
            Topic.GENERAL -> ""
            Topic.COMMUNICATION -> " В общении этот ресурс связан с готовностью учитывать потребности обеих сторон."
            Topic.WORK -> " В работе важно соотнести ожидания с доступными временем и силами."
        }
        return "$roles ${relation(n[0], n[2])}$focus"
    }
}
