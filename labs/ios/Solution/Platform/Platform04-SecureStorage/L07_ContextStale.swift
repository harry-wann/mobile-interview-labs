// L07：背景已經把訂單改成 Filled，畫面上還是 New（Core Data 的 context
// 與「對外只傳獨立的資料」）
// 對應複習站：Platform04SecureStorage 第 6 題「本機資料庫（Core Data、
// SwiftData、Room、Realm）怎麼選？」
//
// 情境：訂單列表從 Core Data 載入。成交回報到達時，在背景把訂單狀態改成 Filled
// 並存檔。
//        Core Data 的每個 NSManagedObjectContext 有自己的物件快取，
// 物件只屬於建立它的 context（Realm 的物件也只能在建立它的執行緒使用，
// 道理一樣）。
// Bug 版：畫面直接拿 viewContext 取出來的 NSManagedObject 來顯示，
// 背景另外用一個 background context 存檔。
//        viewContext 的物件不會自己更新，背景改完存檔之後，畫面上的狀態還是
// New，重新 fetch 也一樣。
// 修正版：資料庫存取包在一個 actor 裡（自己擁有一個 background context），
// 對外只回傳獨立的 struct（OrderModel），
//        讀和寫都經過它。畫面想看最新狀態，就再向它要一份新的 struct。
import CoreData
import Foundation
import LabKit

/// 建立一個放在記憶體裡的 Core Data（兩個版本共用）：只有一個 Order 實體，欄位
/// symbol 與 status
private func makeContainer() -> NSPersistentContainer {
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
    entity.properties = [attribute("symbol"), attribute("status")]
    model.entities = [entity]
    let container = NSPersistentContainer(
        name: "Orders", managedObjectModel: model)
    let description = NSPersistentStoreDescription()
    description.type = NSInMemoryStoreType
    container.persistentStoreDescriptions = [description]
    container.loadPersistentStores { _, error in
        if let error { print("載入失敗：\(error)") }
    }
    return container
}

/// 訂單資料來源（兩個版本各自實作）
private protocol OrderRepo {
    // 先放一張狀態 New 的訂單
    func seed() async
    // 畫面現在能看到的狀態
    func currentStatus() async -> String
    // 成交回報到達：在背景改成 Filled 並存檔
    func backgroundMarkFilled() async
}

/// 演練流程（兩個版本共用）
private func scenario(
    _ log: LabLog, makeRepo: (NSPersistentContainer) -> OrderRepo
) async {
    let repo = makeRepo(makeContainer())
    await repo.seed()
    log("畫面載入訂單，狀態：\(await repo.currentStatus())")
    await repo.backgroundMarkFilled()
    try? await Task.sleep(for: .milliseconds(200))
    log("背景把訂單改成 Filled 並存檔之後，畫面重新讀取的狀態：\(await repo.currentStatus())")
}

// MARK: - Bug 版
private enum L07Bug {
    final class Repo: OrderRepo, @unchecked Sendable {
        private let container: NSPersistentContainer
        // 畫面持有的 NSManagedObject（屬於 viewContext）
        private var shown: NSManagedObject?

        init(container: NSPersistentContainer) {
            self.container = container
        }

        func seed() async {
            let background = container.newBackgroundContext()
            await background.perform {
                let order = NSEntityDescription.insertNewObject(
                    forEntityName: "Order", into: background)
                order.setValue("btcjpy", forKey: "symbol")
                order.setValue("New", forKey: "status")
                try? background.save()
            }
        }

        func currentStatus() async -> String {
            let context = container.viewContext
            return await context.perform { [self] in
                shown =
                    shown
                    ?? (try? context.fetch(
                        NSFetchRequest<NSManagedObject>(entityName: "Order")))?
                    .first
                return shown?.value(forKey: "status") as? String ?? "（沒有）"
            }
        }

        func backgroundMarkFilled() async {
            // 另一個 context：改的是它自己的物件
            let background = container.newBackgroundContext()
            await background.perform {
                let order =
                    (try? background.fetch(
                        NSFetchRequest<NSManagedObject>(entityName: "Order")))?
                    .first
                order?.setValue("Filled", forKey: "status")
                try? background.save()
            }
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { Repo(container: $0) }
    }
}

// MARK: - 修正版
private enum L07Fixed {
    /// 對外傳的資料：獨立的 struct，不是 NSManagedObject
    struct OrderModel {
        let symbol: String
        let status: String
    }

    /// 資料庫存取包在 actor 裡：它自己擁有一個 background context，
    /// 讀和寫都經過它
    actor OrderStore {
        private let context: NSManagedObjectContext

        init(container: NSPersistentContainer) {
            context = container.newBackgroundContext()
        }

        func insert(symbol: String, status: String) async {
            await context.perform { [context] in
                let order = NSEntityDescription.insertNewObject(
                    forEntityName: "Order", into: context)
                order.setValue(symbol, forKey: "symbol")
                order.setValue(status, forKey: "status")
                try? context.save()
            }
        }

        func firstOrder() async -> OrderModel? {
            await context.perform { [context] in
                guard
                    let order =
                        (try? context.fetch(
                            NSFetchRequest<NSManagedObject>(entityName: "Order")
                        ))?.first
                else { return nil }
                // 每次都讀資料庫裡最新的
                context.refresh(order, mergeChanges: false)
                return OrderModel(
                    symbol: order.value(forKey: "symbol") as? String ?? "",
                    status: order.value(forKey: "status") as? String ?? "")
            }
        }

        func markFilled() async {
            await context.perform { [context] in
                let order =
                    (try? context.fetch(
                        NSFetchRequest<NSManagedObject>(entityName: "Order")))?
                    .first
                order?.setValue("Filled", forKey: "status")
                try? context.save()
            }
        }
    }

    final class Repo: OrderRepo, @unchecked Sendable {
        private let store: OrderStore

        init(container: NSPersistentContainer) {
            store = OrderStore(container: container)
        }

        func seed() async {
            await store.insert(symbol: "btcjpy", status: "New")
        }
        // 畫面拿到的是獨立的 struct
        func currentStatus() async -> String {
            await store.firstOrder()?.status ?? "（沒有）"
        }
        func backgroundMarkFilled() async { await store.markFilled() }
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { Repo(container: $0) }
    }
}

// MARK: - 演練設定
extension Platform04SecureStorage {
    static let l07 = Lab(
        id: "L07",
        title: "背景改成 Filled，畫面還是 New",
        question: "第 6 題：本機資料庫（Core Data、SwiftData、Room、Realm）怎麼選？",
        goal:
            "資料庫物件只屬於建立它的 context（執行緒）。把存取包起來，對外只傳獨立的資料，畫面才不會拿著過期、或不能跨執行緒用的物件。",
        steps: [
            "選「Bug 版」按執行，看「背景改成 Filled 並存檔之後，畫面重新讀取的狀態」。",
            "選「修正版」執行：畫面向 OrderStore 重新要一份 struct，狀態是 Filled。",
            "想一想：還有別的修法嗎？（viewContext.automaticallyMergesChangesFromParent = true 也能讓畫面跟上，但物件仍然只能在 viewContext 的執行緒使用；Realm 要求更嚴格：物件只能在建立它的執行緒使用。）",
        ],
        expectBug: "畫面載入時狀態 New；背景改成 Filled 並存檔之後，畫面重新讀取的狀態還是 New。",
        expectFixed: "畫面載入時狀態 New；背景改成 Filled 並存檔之後，畫面重新讀取的狀態是 Filled。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("畫面載入訂單，狀態：New") {
                return
                    "畫面載入訂單時狀態要是 New：seed 要存進一張 New 的訂單，currentStatus 要從 viewContext 取出 NSManagedObject"
            }
            return lines.contains("背景把訂單改成 Filled 並存檔之後，畫面重新讀取的狀態：New")
                ? nil
                : "Bug 還沒重現：畫面要一直拿著 viewContext 的 NSManagedObject，背景用另一個 background context 存檔"
        },
        checkFixed: { lines in
            if !lines.contains("畫面載入訂單，狀態：New") {
                return "畫面載入訂單時狀態要是 New：seed 要存進一張 New 的訂單"
            }
            return lines.contains("背景把訂單改成 Filled 並存檔之後，畫面重新讀取的狀態：Filled")
                ? nil
                : "畫面還是看不到 Filled：讀和寫都要經過 OrderStore（同一個 context），對外只回傳 OrderModel"
        }
    )
}
