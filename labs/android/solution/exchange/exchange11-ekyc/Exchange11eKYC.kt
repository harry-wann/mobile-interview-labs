// Exchange11eKYC：eKYC 與法規
package tw.harry.labs.solution.exchange11ekyc

import tw.harry.labs.kit.LabUnit

val unit = LabUnit(
    id = "exchange-ekyc-compliance",
    code = "Exchange11eKYC",
    title = "eKYC 與法規",
    labs = listOf(l01, l02, l03, l04, l05, l06),   // 新增演練時，把 l01、l02… 加進來
)
