// L01：assign 用在物件上，畫面關掉後通知就當掉
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 下單畫面要實作的協定（兩個版本共用）
@protocol SOLabL01OrderDelegate <NSObject>
- (void)orderDidFill:(NSString *)symbol;
@end

// MARK: - Bug 版
@interface SOLabL01BugOrderController : NSObject
/// 用 assign 指向物件：不持有，但物件釋放後，這個屬性不會變成 nil，變成指向已釋放記憶體的野指標
@property (nonatomic, assign, nullable) id<SOLabL01OrderDelegate> delegate;
/// 通知 delegate 委託成交了。沒有 delegate 就回傳 NO
- (BOOL)notifyFilled;
@end

// MARK: - 修正版
@interface SOLabL01FixedOrderController : NSObject
/// 用 weak：不持有，物件釋放後會自動變成 nil
@property (nonatomic, weak, nullable) id<SOLabL01OrderDelegate> delegate;
- (BOOL)notifyFilled;
@end

NS_ASSUME_NONNULL_END
