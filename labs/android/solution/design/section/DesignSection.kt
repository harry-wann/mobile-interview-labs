package tw.harry.labs.solution

import tw.harry.labs.kit.LabSection
import tw.harry.labs.solution.design02marketdata.unit as design02marketdata
import tw.harry.labs.solution.design03orderassets.unit as design03orderassets
import tw.harry.labs.solution.design04pricealerts.unit as design04pricealerts

/** Mobile System Design 這一區的所有單元（App 首頁依這個順序列出） */
val designSection = LabSection(
    id = "sysdesign",
    title = "Mobile System Design",
    units = listOf(
        design02marketdata,
        design03orderassets,
        design04pricealerts,
    ),
)
