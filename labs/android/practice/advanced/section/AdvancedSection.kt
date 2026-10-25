package tw.harry.labs.practice

import tw.harry.labs.kit.LabSection
import tw.harry.labs.practice.advanced01startup.unit as advanced01startup
import tw.harry.labs.practice.advanced02jank.unit as advanced02jank
import tw.harry.labs.practice.advanced03memory.unit as advanced03memory
import tw.harry.labs.practice.advanced04races.unit as advanced04races
import tw.harry.labs.practice.advanced05highfrequency.unit as advanced05highfrequency
import tw.harry.labs.practice.advanced06crash.unit as advanced06crash
import tw.harry.labs.practice.advanced07appsize.unit as advanced07appsize
import tw.harry.labs.practice.advanced08security.unit as advanced08security
import tw.harry.labs.practice.advanced09architecture.unit as advanced09architecture
import tw.harry.labs.practice.advanced10observability.unit as advanced10observability

/** 資深進階 這一區的所有單元（App 首頁依這個順序列出） */
val advancedSection = LabSection(
    id = "advanced",
    title = "資深進階",
    units = listOf(
        advanced01startup,
        advanced02jank,
        advanced03memory,
        advanced04races,
        advanced05highfrequency,
        advanced06crash,
        advanced07appsize,
        advanced08security,
        advanced09architecture,
        advanced10observability,
    ),
)
