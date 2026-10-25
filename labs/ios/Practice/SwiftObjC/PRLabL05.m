#import "PRLabL05.h"

@implementation PRLabL05FeeTable

- (double)rateForSymbol:(NSString *)symbol {
    return 0.001;                          // 0.1%
}

@end

// MARK: - Bug 版
@implementation PRLabL05BugFeeCalculator

- (double)feeForAmount:(double)amount symbol:(NSString *)symbol {
    // TODO ①：直接用 amount 乘以 [self.table rateForSymbol:symbol] 回傳（不要先檢查 table 是不是 nil）。
    //        table 如果是 nil，對 nil 傳訊息會回傳 0，不會當掉。現在寫死回傳 -1，讓你確認有改到
    return -1;
}

@end

// MARK: - 修正版
@implementation PRLabL05FixedFeeCalculator

- (NSNumber *)feeForAmount:(double)amount symbol:(NSString *)symbol error:(NSError **)error {
    // TODO ②：先檢查 self.table 是不是 nil。是的話：如果 error 不是 NULL，就填入一個 NSError
    //        （domain 隨意、code 用 1、userInfo 的 NSLocalizedDescriptionKey 寫「手續費率表還沒設定」），然後回傳 nil；
    //        不是 nil 才算出手續費回傳。現在沒有檢查，table 是 nil 時會算出 0
    return @(amount * [self.table rateForSymbol:symbol]);
}

@end
