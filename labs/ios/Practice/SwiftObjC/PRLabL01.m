#import "PRLabL01.h"

@implementation PRLabL01BugOrderController

- (BOOL)notifyFilled {
    if (self.delegate == nil) { return NO; }
    [self.delegate orderDidFill:@"BTC/JPY"];
    return YES;
}

@end

@implementation PRLabL01FixedOrderController

- (BOOL)notifyFilled {
    if (self.delegate == nil) { return NO; }
    [self.delegate orderDidFill:@"BTC/JPY"];
    return YES;
}

@end
