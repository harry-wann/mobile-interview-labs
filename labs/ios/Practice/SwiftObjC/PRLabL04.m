#import "PRLabL04Legacy.h"
#import "PRLabL04.h"

@implementation PRLabL04LegacyOrderService

- (instancetype)init {
    if ((self = [super init])) { _symbol = @"BTC/JPY"; }
    return self;
}

- (NSString *)memoForOrder:(NSString *)orderID {
    return nil;
}

@end

@implementation PRLabL04OrderService

- (instancetype)init {
    if ((self = [super init])) { _symbol = @"BTC/JPY"; }
    return self;
}

- (NSString *)memoForOrder:(NSString *)orderID {
    return nil;
}

@end
