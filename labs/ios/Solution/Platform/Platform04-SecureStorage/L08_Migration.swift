// L08：App 更新之後，使用者本機的訂單全部消失了（資料庫 migration，不要用「刪掉重建」）
// 對應複習站：Platform04SecureStorage 第 7 題「資料庫結構改版時，migration 要怎麼做？」
//
// 情境：1.0 版的 Order 實體只有 symbol、status 兩個欄位，使用者手機裡已經存了 2 張訂單。1.1 版新增了 memo 欄位。
//        使用者更新 App 之後，手機裡還是 1.0 版結構的資料庫檔案。演練先用 1.0 版的 model 寫入 2 張訂單，再用 1.1 版的 model 開啟同一個檔案。
// Bug 版：用 addPersistentStore 開啟，沒有給 migration 的選項。Core Data 發現新舊結構不同，丟出 NSPersistentStoreIncompatibleVersionHashError（134100）；
//        程式遇到錯誤就「刪掉資料庫檔案、重新建立」，使用者的資料全沒了。
// 修正版：開啟時加上 NSMigratePersistentStoresAutomaticallyOption 與 NSInferMappingModelAutomaticallyOption（lightweight migration：系統自己推斷新舊模型怎麼對應）。
//        只是新增欄位，系統就能自動把舊資料搬過去。
import CoreData
import Foundation
import LabKit

/// 1.0 版與 1.1 版的資料模型（兩個版本共用）：1.1 版多了 memo 欄位
private func makeModel(version: Int) -> NSManagedObjectModel {
    let model = NSManagedObjectModel()
    let entity = NSEntityDescription()
    entity.name = "Order"
    entity.managedObjectClassName = "NSManagedObject"
    func attribute(_ name: String) -> NSAttributeDescription {
        let a = NSAttributeDescription()
        a.name = name
        a.attributeType = .stringAttributeType
        a.isOptional = true
        return a
    }
    entity.properties = version >= 2 ? [attribute("symbol"), attribute("status"), attribute("memo")] : [attribute("symbol"), attribute("status")]
    model.entities = [entity]
    return model
}

private func removeStore(at url: URL) {
    for suffix in ["", "-wal", "-shm"] {
        try? FileManager.default.removeItem(atPath: url.path + suffix)
    }
}

/// 演練流程（兩個版本共用）：用 1.0 版寫入 2 張訂單，再用 [open] 以 1.1 版開啟，數一數還剩幾張
private func scenario(_ log: LabLog, open: (_ url: URL, _ model: NSManagedObjectModel) -> NSPersistentStoreCoordinator?) {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("orders-\(UUID().uuidString).sqlite")
    defer { removeStore(at: url) }

    // 使用者手機裡的 1.0 版資料庫
    do {
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: makeModel(version: 1))
        _ = try coordinator.addPersistentStore(type: .sqlite, configuration: nil, at: url, options: nil)
        let context = NSManagedObjectContext(.privateQueue)
        context.persistentStoreCoordinator = coordinator
        context.performAndWait {
            for symbol in ["btcjpy", "ethjpy"] {
                let order = NSEntityDescription.insertNewObject(forEntityName: "Order", into: context)
                order.setValue(symbol, forKey: "symbol")
                order.setValue("Filled", forKey: "status")
            }
            try? context.save()
        }
        log("更新前（1.0 版）：本機有 2 張訂單")
    } catch {
        log("建立 1.0 版資料庫失敗：\(error)")
        return
    }

    // 使用者更新 App：用 1.1 版的 model 開啟同一個檔案
    guard let coordinator = open(url, makeModel(version: 2)), !coordinator.persistentStores.isEmpty else {
        log("更新後（1.1 版）：資料庫打不開，App 無法使用")
        return
    }
    let context = NSManagedObjectContext(.privateQueue)
    context.persistentStoreCoordinator = coordinator
    context.performAndWait {
        let count = (try? context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Order")).count) ?? 0
        log("更新後（1.1 版）：本機剩 \(count) 張訂單")
        let order = NSEntityDescription.insertNewObject(forEntityName: "Order", into: context)     // 新版的功能：存一張有備註的訂單
        order.setValue("備註", forKey: "memo")
        log("更新後（1.1 版）：新增一張有備註的訂單\((try? context.save()) != nil ? "成功" : "失敗")")
    }
}

// MARK: - Bug 版
private enum L08Bug {
    static func open(url: URL, model: NSManagedObjectModel) -> NSPersistentStoreCoordinator? {
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        do {
            _ = try coordinator.addPersistentStore(type: .sqlite, configuration: nil, at: url, options: nil)      // 沒有 migration 選項
        } catch {
            removeStore(at: url)                                    // 打不開就刪掉重建：使用者的資料全沒了
            _ = try? coordinator.addPersistentStore(type: .sqlite, configuration: nil, at: url, options: nil)
        }
        return coordinator
    }

    static func run(_ log: LabLog) async {
        scenario(log, open: open)
    }
}

// MARK: - 修正版
private enum L08Fixed {
    static func open(url: URL, model: NSManagedObjectModel) -> NSPersistentStoreCoordinator? {
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        let options: [AnyHashable: Any] = [
            NSMigratePersistentStoresAutomaticallyOption: true,     // 開啟時自動 migration
            NSInferMappingModelAutomaticallyOption: true,           // 讓系統自己推斷新舊模型怎麼對應（lightweight migration）
        ]
        do {
            _ = try coordinator.addPersistentStore(type: .sqlite, configuration: nil, at: url, options: options)
        } catch {
            return nil                                              // 真的打不開：不要刪資料，回報錯誤
        }
        return coordinator
    }

    static func run(_ log: LabLog) async {
        scenario(log, open: open)
    }
}

// MARK: - 演練設定
private func remaining(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("更新後（1.1 版）：本機剩 ") }
        .flatMap { Int($0.replacingOccurrences(of: "更新後（1.1 版）：本機剩 ", with: "").replacingOccurrences(of: " 張訂單", with: "")) }
}

extension Platform04SecureStorage {
    static let l08 = Lab(
        id: "L08",
        title: "App 更新後，本機訂單全部消失",
        question: "第 7 題：資料庫結構改版時，migration 要怎麼做？",
        goal: "資料庫結構改版時要有明確的 migration 路徑。新增欄位這種簡單變更，用 lightweight migration 讓系統自動搬資料，不要刪掉重建。",
        steps: [
            "選「Bug 版」按執行，看「更新後（1.1 版）：本機剩幾張訂單」。主控台會印出 Core Data 的 134100 錯誤，那就是 Bug 版遇到的錯誤。",
            "選「修正版」執行：加上兩個 migration 選項，2 張訂單都還在。",
            "想一想：什麼時候可以刪掉重建？（只有可以從伺服器重新抓回來的快取；只要有使用者設定、草稿、尚未同步的資料，就是資料遺失）",
        ],
        expectBug: "更新前（1.0 版）本機有 2 張訂單；更新後（1.1 版）本機剩 0 張訂單。",
        expectFixed: "更新前本機有 2 張訂單；更新後本機剩 2 張訂單。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = remaining(lines) else { return "更新後資料庫打不開：open 要用 addPersistentStore 加上 store，並回傳 coordinator" }
            if !lines.contains("更新後（1.1 版）：新增一張有備註的訂單成功") { return "資料庫要能使用：打不開時刪掉檔案後，要再用 addPersistentStore 重新建立" }
            if n != 0 { return "Bug 還沒重現（剩 \(n) 張）：Bug 版開啟時不給 migration 選項，打不開就 removeStore 刪掉重建" }
            return nil
        },
        checkFixed: { lines in
            guard let n = remaining(lines) else { return "更新後資料庫打不開：開啟時要加上 NSMigratePersistentStoresAutomaticallyOption 與 NSInferMappingModelAutomaticallyOption" }
            if !lines.contains("更新後（1.1 版）：新增一張有備註的訂單成功") { return "更新後新增訂單失敗：open 要用 addPersistentStore 加上 store，並回傳 coordinator" }
            return n == 2 ? nil : "更新後只剩 \(n) 張訂單：開啟時加上 NSMigratePersistentStoresAutomaticallyOption 與 NSInferMappingModelAutomaticallyOption，不要刪資料庫"
        }
    )
}
