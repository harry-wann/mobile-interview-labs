// Platform03BackgroundPush：背景執行與推播
import LabKit

enum Platform03BackgroundPush {
    static let unit = LabUnit(
        id: "platform-background-push",
        code: "Platform03BackgroundPush",
        title: "背景執行與推播",
        labs: [l02, l04, l05, l06, l07]   // 新增演練時，把 l01、l02… 加進來
    )
}
