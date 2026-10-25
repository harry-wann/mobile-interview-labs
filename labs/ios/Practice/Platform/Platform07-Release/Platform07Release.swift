// Platform07Release：建置、簽署與發布
import LabKit

enum Platform07Release {
    static let unit = LabUnit(
        id: "platform-build-sign-release",
        code: "Platform07Release",
        title: "建置、簽署與發布",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04]
    )
}
