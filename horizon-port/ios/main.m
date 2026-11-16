#import <UIKit/UIKit.h>

extern const char *horizon_ios_diagnostics(void);
extern int horizon_ios_self_test(void);

@interface HorizonAppDelegate : UIResponder <UIApplicationDelegate>
@property (nonatomic, strong) UIWindow *window;
@end

@implementation HorizonAppDelegate

- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];

    UIViewController *controller = [[UIViewController alloc] init];
    controller.view.backgroundColor = [UIColor colorWithRed:0.055 green:0.075 blue:0.09 alpha:1.0];

    UILabel *title = [[UILabel alloc] init];
    title.text = @"HORIZON";
    title.textColor = [UIColor colorWithRed:0.45 green:0.92 blue:0.55 alpha:1.0];
    title.font = [UIFont monospacedSystemFontOfSize:34 weight:UIFontWeightBold];
    title.textAlignment = NSTextAlignmentCenter;

    UILabel *subtitle = [[UILabel alloc] init];
    subtitle.text = @"Rust client · iOS development shell";
    subtitle.textColor = [UIColor colorWithWhite:0.72 alpha:1.0];
    subtitle.font = [UIFont monospacedSystemFontOfSize:13 weight:UIFontWeightRegular];
    subtitle.textAlignment = NSTextAlignmentCenter;

    const char *raw = horizon_ios_diagnostics();
    NSString *diagnostics = raw ? [NSString stringWithUTF8String:raw] : @"diagnostics unavailable";

    UILabel *body = [[UILabel alloc] init];
    body.text = diagnostics;
    body.textColor = [UIColor colorWithWhite:0.9 alpha:1.0];
    body.font = [UIFont monospacedSystemFontOfSize:13 weight:UIFontWeightRegular];
    body.numberOfLines = 0;
    body.textAlignment = NSTextAlignmentLeft;
    body.translatesAutoresizingMaskIntoConstraints = NO;

    UIView *bodyCard = [[UIView alloc] init];
    bodyCard.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.055];
    bodyCard.layer.cornerRadius = 12.0;
    bodyCard.layer.masksToBounds = YES;
    [bodyCard addSubview:body];
    [NSLayoutConstraint activateConstraints:@[
        [body.leadingAnchor constraintEqualToAnchor:bodyCard.leadingAnchor constant:16.0],
        [body.trailingAnchor constraintEqualToAnchor:bodyCard.trailingAnchor constant:-16.0],
        [body.topAnchor constraintEqualToAnchor:bodyCard.topAnchor constant:16.0],
        [body.bottomAnchor constraintEqualToAnchor:bodyCard.bottomAnchor constant:-16.0],
    ]];

    UILabel *footer = [[UILabel alloc] init];
    footer.text = [NSString stringWithFormat:@"core self-test: %@\nUnofficial build · no Mojang assets bundled",
                                             horizon_ios_self_test() == 0 ? @"ok" : @"failed"];
    footer.textColor = [UIColor colorWithWhite:0.55 alpha:1.0];
    footer.font = [UIFont monospacedSystemFontOfSize:11 weight:UIFontWeightRegular];
    footer.numberOfLines = 0;
    footer.textAlignment = NSTextAlignmentCenter;

    UIStackView *stack = [[UIStackView alloc] initWithArrangedSubviews:@[ title, subtitle, bodyCard, footer ]];
    stack.axis = UILayoutConstraintAxisVertical;
    stack.spacing = 18.0;
    stack.translatesAutoresizingMaskIntoConstraints = NO;
    [controller.view addSubview:stack];

    UILayoutGuide *safe = controller.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [stack.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:24.0],
        [stack.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor constant:-24.0],
        [stack.centerYAnchor constraintEqualToAnchor:safe.centerYAnchor],
    ]];

    self.window.rootViewController = controller;
    [self.window makeKeyAndVisible];
    return YES;
}

@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass([HorizonAppDelegate class]));
    }
}
