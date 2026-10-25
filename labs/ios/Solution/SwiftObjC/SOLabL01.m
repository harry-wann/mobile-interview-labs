#import "SOLabL01.h"

@implementation SOLabL01BugOrderController

- (BOOL)notifyFilled {
    if (self.delegate == nil) { return NO; }
    [self.delegate orderDidFill:@"BTC/JPY"];
    return YES;
}

@end

@implementation SOLabL01FixedOrderController

- (BOOL)notifyFilled {
    if (self.delegate == nil) { return NO; }
    [self.delegate orderDidFill:@"BTC/JPY"];
    return YES;
}

@end
