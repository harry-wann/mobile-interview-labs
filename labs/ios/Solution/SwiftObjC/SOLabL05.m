#import "SOLabL05.h"

@implementation SOLabL05FeeTable

- (double)rateForSymbol:(NSString *)symbol {
    return 0.001;                          // 0.1%
}

@end

// MARK: - Bug 版
@implementation SOLabL05BugFeeCalculator

- (double)feeForAmount:(double)amount symbol:(NSString *)symbol {
    return amount * [self.table rateForSymbol:symbol];    // table 是 nil：對 nil 傳訊息回傳 0，不會當掉，也沒有任何錯誤
}

@end

// MARK: - 修正版
@implementation SOLabL05FixedFeeCalculator

- (NSNumber *)feeForAmount:(double)amount symbol:(NSString *)symbol error:(NSError **)error {
    if (self.table == nil) {                               // 先檢查：沒有率表就明確回報錯誤
        if (error) {
            *error = [NSError errorWithDomain:@"SOLabL05" code:1
                                     userInfo:@{NSLocalizedDescriptionKey: @"手續費率表還沒設定"}];
        }
        return nil;
    }
    return @(amount * [self.table rateForSymbol:symbol]);
}

@end
