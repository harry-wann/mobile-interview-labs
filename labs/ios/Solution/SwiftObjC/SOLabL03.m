#import "SOLabL03.h"

static NSInteger bugAlive = 0;
static NSInteger fixedAlive = 0;

// MARK: - Bug 版
@implementation SOLabL03BugPriceListener

+ (NSInteger)aliveCount { return bugAlive; }

- (instancetype)init {
    if ((self = [super init])) { bugAlive += 1; }
    return self;
}

- (void)dealloc { bugAlive -= 1; }

- (void)start {
    // 編譯器會警告：Capturing 'self' strongly in this block is likely to lead to a retain cycle。這裡刻意不理它
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-retain-cycles"
    self.handler = ^(double price) {
        self.lastPrice = price;            // block 直接用 self：物件持有 block，block 又持有物件，繞成一圈
    };
#pragma clang diagnostic pop
}

@end

// MARK: - 修正版
@implementation SOLabL03FixedPriceListener

+ (NSInteger)aliveCount { return fixedAlive; }

- (instancetype)init {
    if ((self = [super init])) { fixedAlive += 1; }
    return self;
}

- (void)dealloc { fixedAlive -= 1; }

- (void)start {
    __weak typeof(self) weakSelf = self;                      // 步驟 1：block 外面先拿一個 weak 參考
    self.handler = ^(double price) {
        __strong typeof(weakSelf) strongSelf = weakSelf;      // 步驟 2：block 一開始，轉成 strong
        if (!strongSelf) { return; }                          // self 已經釋放就直接結束
        strongSelf.lastPrice = price;
    };
}

@end
