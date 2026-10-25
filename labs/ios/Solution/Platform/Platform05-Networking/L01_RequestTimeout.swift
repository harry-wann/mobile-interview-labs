// L01：逾時明明設 1 秒，下載卻等了 2 秒多還不逾時（URLSession 的兩種逾時）
// 對應複習站：Platform05Networking 第 1 題「URLSession 和 OkHttp 怎麼對照？預設的逾時和傳輸安全規則差在哪？」
//
// 情境：App 下載一份資產明細。本機假伺服器每 0.2 秒送出 1 個位元組，共 12 個，全部送完要 2.2 秒（網速很慢，但連線沒有斷）。
//       工程師希望「整個下載最多等 1 秒，超過就逾時，顯示重試按鈕」。
// Bug 版：設 timeoutIntervalForRequest = 1。這個值是「兩次收到資料之間最久等多久」，每收到一點資料就重新計時，
//        伺服器每 0.2 秒就送一點，永遠不會逾時，等了約 2.2 秒才完成。
// 修正版：改設 timeoutIntervalForResource = 1。它才是「整個請求的總時限」，1 秒一到就逾時。
import Foundation
import LabKit

/// 下載一次（兩個版本共用）：configure 負責設定 URLSessionConfiguration 的逾時
private func download(_ log: LabLog, configure: (URLSessionConfiguration) -> Void) async {
    guard let server = try? P05LocalServer(handler: { _, writer in
        writer.head(200, ["Content-Length": "12"])
        for i in 0..<12 {
            if i > 0 { writer.pause(milliseconds: 200) }    // 每 0.2 秒送 1 個位元組：慢，但一直有資料
            writer.body("x")
        }
    }) else {
        log("本機伺服器啟動失敗")
        return
    }
    defer { server.stop() }

    let config = URLSessionConfiguration.ephemeral
    configure(config)
    let session = URLSession(configuration: config)
    defer { session.invalidateAndCancel() }
    log("設定：request 逾時 \(Int(config.timeoutIntervalForRequest)) 秒，resource 逾時 \(Int(config.timeoutIntervalForResource)) 秒")

    let start = DispatchTime.now()
    do {
        let (data, _) = try await session.data(from: server.url("/statement"))
        log("沒有逾時：收到 \(data.count) 個位元組，花了 \(p05ElapsedMs(since: start)) 毫秒")
    } catch let error as URLError where error.code == .timedOut {
        log("逾時（URLError.timedOut）：花了 \(p05ElapsedMs(since: start)) 毫秒")
    } catch {
        log("其他錯誤：\(error)")
    }
}

// MARK: - Bug 版
private enum L01Bug {
    static func run(_ log: LabLog) async {
        await download(log) { config in
            config.timeoutIntervalForRequest = 1            // 以為這是「整個請求最多 1 秒」：其實是兩次收到資料之間的空檔
        }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func run(_ log: LabLog) async {
        await download(log) { config in
            config.timeoutIntervalForResource = 1           // 整個請求的總時限：1 秒到就逾時
        }
    }
}

// MARK: - 演練設定
private func elapsed(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.contains("花了") }),
          let range = line.range(of: "花了 "),
          let end = line.range(of: " 毫秒") else { return nil }
    return Int(line[range.upperBound..<end.lowerBound])
}

extension Platform05Networking {
    static let l01 = Lab(
        id: "L01",
        title: "逾時設 1 秒，下載卻等了 2 秒多",
        question: "第 1 題：URLSession 和 OkHttp 怎麼對照？預設的逾時和傳輸安全規則差在哪？",
        goal: "伺服器網速很慢但一直有資料送來。讓下載在 1 秒內逾時，要設對 URLSession 的哪一個逾時。",
        steps: [
            "選「Bug 版」執行，看最後兩行：request 逾時設成 1 秒，下載有沒有逾時、花了多久。",
            "想一想：伺服器每 0.2 秒就送一點資料，「兩次收到資料之間最久等多久」會不會超過 1 秒？",
            "選「修正版」執行，看改設另一個逾時之後，是不是約 1 秒就逾時。",
            "進階：Android 的 OkHttp 也有同樣的差別：readTimeout 是兩次讀取之間的空檔，callTimeout 才是整個呼叫的總時限。",
        ],
        expectBug: "「沒有逾時：收到 12 個位元組，花了 2200 毫秒」左右（超過 2000 毫秒）。",
        expectFixed: "「逾時（URLError.timedOut）：花了 1100 毫秒」左右（不到 1800 毫秒）。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("設定：request 逾時 1 秒，resource 逾時 604800 秒") {
                return "Bug 版要把 timeoutIntervalForRequest 設成 1（resource 逾時保持預設）"
            }
            guard let ms = elapsed(lines), lines.contains(where: { $0.hasPrefix("沒有逾時") }) else {
                return "Bug 還沒重現：伺服器一直有資料送來，只設 request 逾時時，下載應該不會逾時"
            }
            return ms >= 2000 ? nil : "Bug 版應該等滿約 2200 毫秒才完成（實際 \(ms) 毫秒）"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("設定：") && $0.contains("resource 逾時 1 秒") }) {
                return "修正版要把 timeoutIntervalForResource 設成 1"
            }
            guard let ms = elapsed(lines), lines.contains(where: { $0.hasPrefix("逾時") }) else {
                return "下載應該在 1 秒左右逾時，但它沒有逾時"
            }
            return ms < 1800 ? nil : "逾時太晚了（\(ms) 毫秒）：resource 逾時應該設成 1 秒"
        }
    )
}
