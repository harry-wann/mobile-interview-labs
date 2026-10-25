package tw.harry.labs.solution

import tw.harry.labs.kit.LabSection
import tw.harry.labs.solution.testing01strategy.unit as testing01strategy
import tw.harry.labs.solution.testing03androidtesting.unit as testing03androidtesting
import tw.harry.labs.solution.testing04e2e.unit as testing04e2e
import tw.harry.labs.solution.testing05aitesting.unit as testing05aitesting
import tw.harry.labs.solution.testing06ci.unit as testing06ci

/** AI 自動化測試 這一區的所有單元（App 首頁依這個順序列出） */
val testingSection = LabSection(
    id = "testing",
    title = "AI 自動化測試",
    units = listOf(
        testing01strategy,
        testing03androidtesting,
        testing04e2e,
        testing05aitesting,
        testing06ci,
    ),
)
