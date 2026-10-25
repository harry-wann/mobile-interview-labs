// L01：assign 用在物件上，畫面關掉後通知就當掉
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 下單畫面要實作的協定（兩個版本共用）
@protocol PRLabL01OrderDelegate <NSObject>
- (void)orderDidFill:(NSString *)symbol;
@end

// MARK: - Bug 版
@interface PRLabL01BugOrderController : NSObject
// TODO ①：delegate 要用 assign（不持有，物件釋放後變成野指標）。現在寫的是 weak，所以不會當掉；改成 assign
@property (nonatomic, weak, nullable) id<PRLabL01OrderDelegate> delegate;
/// 通知 delegate 委託成交了。沒有 delegate 就回傳 NO
- (BOOL)notifyFilled;
@end

// MARK: - 修正版
@interface PRLabL01FixedOrderController : NSObject
// TODO ②：現在 delegate 用 strong（持有畫面，畫面關掉後還活著）。改成「不持有，而且物件釋放後會自動變成 nil」的特性
@property (nonatomic, strong, nullable) id<PRLabL01OrderDelegate> delegate;
- (BOOL)notifyFilled;
@end

NS_ASSUME_NONNULL_END
