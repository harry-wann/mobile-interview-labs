// Platform04SecureStorage：安全儲存與資料庫
import LabKit

enum Platform04SecureStorage {
    static let unit = LabUnit(
        id: "platform-secure-storage-db",
        code: "Platform04SecureStorage",
        title: "安全儲存與資料庫",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l05, l06, l07, l08]
    )
}
