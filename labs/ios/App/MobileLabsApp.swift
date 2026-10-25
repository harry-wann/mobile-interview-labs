import SwiftUI
import LabKit

/// Mobile Labs：首頁依區列出每個單元與演練。
/// 程式碼在兩個 Swift Package：Practice（練習，你寫的）與 Solution（解答），
/// 結構一模一樣。
@main
struct MobileLabsApp: App {
    var body: some Scene {
        WindowGroup {
            LabRootView(practice: Catalog.practice, solution: Catalog.solution)
        }
    }
}
