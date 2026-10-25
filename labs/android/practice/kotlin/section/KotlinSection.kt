package tw.harry.labs.practice

import tw.harry.labs.kit.LabSection
import tw.harry.labs.practice.kotlin01types.unit as kotlin01types
import tw.harry.labs.practice.kotlin02functions.unit as kotlin02functions
import tw.harry.labs.practice.kotlin03coroutines.unit as kotlin03coroutines
import tw.harry.labs.practice.kotlin04coroutinesync.unit as kotlin04coroutinesync
import tw.harry.labs.practice.kotlin05flow.unit as kotlin05flow
import tw.harry.labs.practice.kotlin06recomposition.unit as kotlin06recomposition
import tw.harry.labs.practice.kotlin07sideeffects.unit as kotlin07sideeffects
import tw.harry.labs.practice.kotlin08composeperformance.unit as kotlin08composeperformance
import tw.harry.labs.practice.kotlin09composearchitecture.unit as kotlin09composearchitecture
import tw.harry.labs.practice.kotlin10javainterop.unit as kotlin10javainterop
import tw.harry.labs.practice.kotlin11composestate.unit as kotlin11composestate

/** Kotlin & Compose 這一區的所有單元（App 首頁依這個順序列出） */
val kotlinSection = LabSection(
    id = "kotlin",
    title = "Kotlin & Compose",
    units = listOf(
        kotlin01types,
        kotlin02functions,
        kotlin03coroutines,
        kotlin04coroutinesync,
        kotlin05flow,
        kotlin06recomposition,
        kotlin07sideeffects,
        kotlin08composeperformance,
        kotlin09composearchitecture,
        kotlin10javainterop,
        kotlin11composestate,
    ),
)
