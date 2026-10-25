// Platform06DeepLink：Deep Link
import LabKit

enum Platform06DeepLink {
    static let unit = LabUnit(
        id: "platform-deeplink",
        code: "Platform06DeepLink",
        title: "Deep Link",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05]
    )
}
