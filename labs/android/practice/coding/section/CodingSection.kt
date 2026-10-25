package tw.harry.labs.practice

import tw.harry.labs.kit.LabSection
import tw.harry.labs.practice.coding01datastructures.unit as coding01datastructures
import tw.harry.labs.practice.coding02timestreams.unit as coding02timestreams
import tw.harry.labs.practice.coding03exchangealgorithms.unit as coding03exchangealgorithms
import tw.harry.labs.practice.coding04async.unit as coding04async

/** Live Coding 這一區的所有單元（App 首頁依這個順序列出） */
val codingSection = LabSection(
    id = "livecoding",
    title = "Live Coding",
    units = listOf(
        coding01datastructures,
        coding02timestreams,
        coding03exchangealgorithms,
        coding04async,
    ),
)
