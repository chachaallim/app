package com.chachaallim.app

internal object ReminderCommand {
    private val timingPrefix = Regex(
        """^(?:(?:차에서\s*)?내릴\s*때(?:는)?|(?:다음\s*)?주차(?:할\s*때|하면|하고\s*나면|한\s*후))[\s,，]*"""
    )
    private val commandPattern = Regex(
        """^([\p{L}\p{N}][\p{L}\p{N}\s·\-]{0,59}?)\s*""" +
            """(?:(?:챙겨|가져가)\s*(?:줘|주세요|달라고\s*알려\s*(?:줘|주세요))|""" +
            """(?:챙기|가져가)라고\s*(?:알려|말해)\s*(?:줘|주세요))$"""
    )
    private val sentenceEnding = Regex("""[.!?。！？]+$""")
    private val whitespace = Regex("""\s+""")
    private val unsupportedWords = Regex(
        """(?:^| )(?:안|못|빼고|말고|대신|내일|모레|오늘|나중에|도착하면|출발할 때)(?: |$)"""
    )

    private val parkingTime = Regex(
        """(?<!\S)(?:(?:차에서\s*)?(?:내릴\s*때(?:는)?|내리기\s*전에|내리거든)|하차할\s*때|""" +
            """(?:다음\s*)?주차(?:를\s*마치면|하고\s*나서|하고\s*나면|\s*다\s*하면|""" +
            """하자마자|하거든|하면|할\s*때|한\s*후|\s*때)|차\s*세우(?:고\s*나면|면))(?![가-힣])[\s,，]*"""
    )
    private val otherTime = Regex(
        """(?:^|\s)(?:지금|당장|나중에|언젠가|내일\s*때)(?=\s|$)|""" +
            """(?:^|\s)(?:내일|모레|오늘|(?:월|화|수|목|금|토|일)요일)\s*(?:아침|저녁|오전|오후)?\s*에(?=\s|$)|""" +
            """(?:\d+|[한두세네다섯여섯일곱여덟아홉열]+)\s*(?:시\s*(?:반\s*)?에|(?:시간|분|일)\s*(?:뒤|후))(?=\s|$|에)|""" +
            """(?:때|거든|하면|나면|(?<!잊)으면|되면|멈추면|후에|전에)(?=\s|[,.!?]|$)"""
    )
    private val standaloneDate = Regex("""(?:^|\s)(?:내일|모레|오늘|다음\s*(?:주|달))(?=\s|$)""")
    private val reportedRequest = Regex(
        """(.+?)(으라고|라고|으라|라|게)\s*(?:(?:알려|말해|전해|얘기해|일러|귀띔해|해|캐)\s*(?:줘|주세요|줄래)|그래)"""
    )
    private val statementRequest = Regex(
        """(.+?)\s*(있|없)다고\s*(?:알려|말해|전해|얘기해|귀띔해)\s*(?:줘|주세요|줄래)"""
    )
    private val actionClauseRequest = Regex(
        """(.+는)\s*(?:거(?:를)?|것(?:을)?|걸)\s*(?:(?:알려|말해|일러|얘기해)\s*(?:줘|주세요)|""" +
            """(?:잊지|빼먹지|까먹지)\s*않게\s*(?:해|알려)\s*(?:줘|주세요|줄래|부탁해)|""" +
            """잊으면\s*안\s*된다고\s*얘기해\s*줘)"""
    )
    private val avoidRequest = Regex("""(.+?지)\s*않도록\s*해\s*(?:줘|주세요)""")
    private val negativeEnding = Regex("""\s*지\s*(?:말|마)$""")
    private val locationStatement = Regex("""([^.!?]+?에)\s+([^.!?]+?)\s+있어[.!?]\s*(.+)""")
    private val incompleteReference = Regex("""(?:^|\s)(?:(?:그|저|이)(?:거|것)|[그저이]걸)(?:만|를|을|은|는)?(?=\s|$)""")
    private val compoundEnding = Regex("""(꺼내|챙겨)\s*가$""")
    private val locationOfItem = Regex("""([가-힣]+)에\s+(\S+)\s+꺼내 가""")

    fun supportsParkingTime(command: String): Boolean {
        val timing = parkingTime.findAll(command).toList()
        if (timing.size > 1 || timing.any {
                val rest = command.substring(it.range.last + 1).trimStart()
                rest.startsWith("말고 ") || rest.startsWith("대신 ")
            }
        ) return false
        val remaining = parkingTime.replace(command, " ")
        return !otherTime.containsMatchIn(remaining) &&
            (timing.isNotEmpty() || !standaloneDate.containsMatchIn(remaining))
    }

    fun hasUnresolvedReference(command: String): Boolean =
        incompleteReference.containsMatchIn(command.trim().replace(sentenceEnding, ""))

    fun parkingMessage(command: String): String? {
        val normalized = command.trim().replace(sentenceEnding, "").trim().replace(whitespace, " ")
        if (!supportsParkingTime(normalized)) return null

        var request = normalized.replace(parkingTime, " ").trim().replace(whitespace, " ")
        locationStatement.matchEntire(request)?.destructured?.let { (place, item, action) ->
            val instruction = reportedRequest.matchEntire(action)?.groupValues?.get(1) ?: return null
            if (' ' in instruction || item.endsWith('이') || item.endsWith('가')) return null
            request = "$place 있는 $item $action"
        }
        if (request.any { it in ".!?。！？" } || hasUnresolvedReference(request)) return null

        statementRequest.matchEntire(request)?.destructured?.let { (subject, predicate) ->
            return subject.trim() + " " + predicate + "어요."
        }
        avoidRequest.matchEntire(request)?.let { match ->
            val action = match.groupValues[1].trim()
            return if (' ' in action) "$action 마세요." else null
        }
        actionClauseRequest.matchEntire(request)?.let { match ->
            return match.groupValues[1] + " 걸 잊지 마세요."
        }
        val (action, quote) = reportedRequest.matchEntire(request)?.destructured ?: return null
        return politeInstruction(action, if (quote.startsWith('으')) "으세요" else "세요")
    }

    private fun politeInstruction(action: String, ending: String): String? {
        val body = action.trim()
        val negative = negativeEnding.find(body)
        if (negative != null) {
            val instruction = body.substring(0, negative.range.first)
            return if (' ' in instruction) instruction + "지 마세요." else null
        }
        if (' ' !in body || (body.endsWith('이') && ending == "세요")) return null
        val spaced = compoundEnding.replace(body) { match -> match.groupValues[1] + " 가" }
        val placed = locationOfItem.matchEntire(spaced)?.destructured?.let { (place, item) ->
            val withParticle = if (item.endsWith('을') || item.endsWith('를')) item else {
                val last = item.last()
                val particle = if (last in '가'..'힣' && (last - '가') % 28 != 0) "을" else "를"
                item + particle
            }
            "${place}에서 $withParticle 꺼내 가"
        } ?: spaced
        return placed + ending + "."
    }

    fun itemFrom(command: String): String? {
        val normalized = command.trim().replace(sentenceEnding, "").trim().replace(whitespace, " ")
        val request = normalized.replaceFirst(timingPrefix, "")
        val objectPhrase = commandPattern.matchEntire(request)?.groupValues?.get(1)?.trim()
            ?: return null
        if (objectPhrase == "을" || objectPhrase == "를" || unsupportedWords.containsMatchIn(objectPhrase)) return null
        return removeObjectParticle(objectPhrase)
    }

    private fun removeObjectParticle(phrase: String): String {
        if (phrase.length < 2 || phrase.last() !in "을를") return phrase
        val noun = phrase.dropLast(1).trimEnd()
        val last = noun.last()
        if (last !in '가'..'힣') return if (last.isDigit() || last in 'A'..'Z' || last in 'a'..'z') noun else phrase
        val hasFinalConsonant = (last - '가') % 28 != 0
        return if (phrase.last() == (if (hasFinalConsonant) '을' else '를')) noun else phrase
    }
}
