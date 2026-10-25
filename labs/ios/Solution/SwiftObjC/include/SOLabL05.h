// L05：對 nil 傳訊息不會當掉，手續費被靜默算成 0
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 手續費率表（兩個版本共用）
@interface SOLabL05FeeTable : NSObject
- (double)rateForSymbol:(NSString *)symbol;
@end

// MARK: - Bug 版
@interface SOLabL05BugFeeCalculator : NSObject
/// 手續費率表。忘了設定就是 nil
@property (nonatomic, strong, nullable) SOLabL05FeeTable *table;
- (double)feeForAmount:(double)amount symbol:(NSString *)symbol;
@end

// MARK: - 修正版
@interface SOLabL05FixedFeeCalculator : NSObject
@property (nonatomic, strong, nullable) SOLabL05FeeTable *table;
/// 手續費；率表還沒設定時回傳 nil 並填入 error（Swift 會看到 throws）
- (nullable NSNumber *)feeForAmount:(double)amount symbol:(NSString *)symbol error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END
