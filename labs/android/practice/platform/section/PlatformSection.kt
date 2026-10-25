package tw.harry.labs.practice

import tw.harry.labs.kit.LabSection
import tw.harry.labs.practice.platform01lifecycle.unit as platform01lifecycle
import tw.harry.labs.practice.platform02threading.unit as platform02threading
import tw.harry.labs.practice.platform03backgroundpush.unit as platform03backgroundpush
import tw.harry.labs.practice.platform04securestorage.unit as platform04securestorage
import tw.harry.labs.practice.platform05networking.unit as platform05networking
import tw.harry.labs.practice.platform06deeplink.unit as platform06deeplink
import tw.harry.labs.practice.platform07release.unit as platform07release
import tw.harry.labs.practice.platform08modules.unit as platform08modules

/** iOS × Android 平台對照 這一區的所有單元（App 首頁依這個順序列出） */
val platformSection = LabSection(
    id = "platform",
    title = "iOS × Android 平台對照",
    units = listOf(
        platform01lifecycle,
        platform02threading,
        platform03backgroundpush,
        platform04securestorage,
        platform05networking,
        platform06deeplink,
        platform07release,
        platform08modules,
    ),
)
