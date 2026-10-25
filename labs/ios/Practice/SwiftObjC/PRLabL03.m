#import "PRLabL03.h"

static NSInteger bugAlive = 0;
static NSInteger fixedAlive = 0;

// MARK: - Bug 版
@implementation PRLabL03BugPriceListener

+ (NSInteger)aliveCount { return bugAlive; }

- (instancetype)init {
    if ((self = [super init])) { bugAlive += 1; }
    return self;
}

- (void)dealloc { bugAlive -= 1; }

- (void)start {
    // TODO ①：Bug 版：handler 的 block 裡直接用 self 更新 lastPrice（不要 weak／strong），讓物件與 block 互相持有。
    //        編譯器會警告 Capturing 'self' strongly in this block is likely to lead to a retain cycle；
    //        要刻意不理它，可以用 #pragma clang diagnostic ignored "-Warc-retain-cycles" 包起來。
    //        現在寫的是 weak／strong 兩步（正確的寫法），所以不會洩漏
    __weak typeof(self) weakSelf = self;
    self.handler = ^(double price) {
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf) { return; }
        strongSelf.lastPrice = price;
    };
}

@end

// MARK: - 修正版
@implementation PRLabL03FixedPriceListener

+ (NSInteger)aliveCount { return fixedAlive; }

- (instancetype)init {
    if ((self = [super init])) { fixedAlive += 1; }
    return self;
}

- (void)dealloc { fixedAlive -= 1; }

- (void)start {
    // TODO ②：現在 block 裡直接用 self（Bug 的寫法）。改成 weak／strong 兩步：
    //        步驟 1：block 外面先用 __weak 拿一個 weakSelf；步驟 2：block 一開始用 __strong 轉成 strongSelf，並檢查是不是 nil，是就直接 return
    // 下面的 pragma 只是為了不要出現警告，改完 block 之後可以刪掉
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-retain-cycles"
    self.handler = ^(double price) {
        self.lastPrice = price;
    };
#pragma clang diagnostic pop
}

@end
